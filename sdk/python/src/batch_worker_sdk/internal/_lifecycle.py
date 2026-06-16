""":class:`BatchPlatformClient` 的分阶段停机实现。

对齐 Java SDK ``BatchPlatformClient.stop(Duration timeout)`` —— 把总超时
预算按阶段切片,每阶段都从 *剩余* 预算计算自己的 deadline,某一阶段超时
仅 WARN 不向上抛,确保后续阶段(尤其是 ``/deactivate``)仍能执行。

阶段预算占比(与 Java 端 contract fixture ``12-stop-with-timeout`` 保持
一致):

================ ===== ==================================================
阶段              比例   动作
================ ===== ==================================================
draining flag     0%    同步翻 ``dispatcher.draining = True``
Kafka consumer   20%    ``await client.consumer.stop()``
in-flight drain  60%    轮询 ``dispatcher.in_flight_count()`` 直到归零
scheduler stop   20%    停止心跳 / 租约续约调度器
================ ===== ==================================================

每个阶段的 deadline 都根据 *剩余* 总预算重新计算,所以提前完成的阶段会
把空余时间让给下一个阶段,而不是无谓延长总 wall-clock。

本函数 **不** 直接 import :class:`BatchPlatformClient`(在 client.py 里),
以字符串前向引用 + duck-typing 规避循环 import。期望的 ``client`` 属性:

- ``client.consumer`` —— Kafka 消费者,需 ``async stop()``,可选
  ``async start_draining()``。
- ``client.dispatcher`` —— 暴露 ``in_flight_count() -> int``、
  ``in_flight_task_ids() -> list[int]``、``start_draining()``,可选
  ``async stop()``。
- ``client.schedulers`` —— 含 ``async stop()`` 的可迭代对象(心跳调度器、
  租约续约调度器)。
- ``client.deactivate()`` —— 可选;HTTP /deactivate 调用。 best-effort 调用
  并吞掉异常(即便失败,orch 端 120s 心跳超时也会自动回收 worker)。
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from typing import Any

logger = logging.getLogger(__name__)

# 阶段预算占比(累加 <= 1.0;留 0% 余量是可接受的)。
_KAFKA_FRACTION = 0.20
_DRAIN_FRACTION = 0.60
_SCHEDULER_FRACTION = 0.20

# in-flight drain 阶段的轮询间隔。100ms 与 Java 端 spin 循环对齐:既能
# 快速响应,又不会刷日志。
_DRAIN_POLL_INTERVAL = 0.1


def _remaining(deadline: float) -> float:
    """返回距 ``deadline`` 还剩的秒数,不会为负。"""
    loop = asyncio.get_event_loop()
    return max(0.0, deadline - loop.time())


async def _phase_stop_kafka(client: Any, budget: float) -> None:
    """阶段 2:在 ``budget`` 秒内停止 Kafka 消费者。

    依次按 ``_kafka``(:class:`BatchPlatformClient` 内部字段)/ ``consumer``
    (临时 ``run_worker`` 入口)两种命名查找,首个非 None 命中。
    """
    consumer = getattr(client, "_kafka", None) or getattr(client, "consumer", None)
    if consumer is None:
        return
    stop = getattr(consumer, "stop", None)
    if stop is None:
        return
    try:
        await asyncio.wait_for(stop(), timeout=max(budget, 0.001))
    except TimeoutError:
        logger.warning(
            "stop_with_timeout: Kafka consumer stop overran %.3fs budget; continuing",
            budget,
        )
    except Exception as exc:
        logger.warning("stop_with_timeout: Kafka consumer stop failed: %s; continuing", exc)


async def _phase_drain_in_flight(client: Any, deadline: float) -> None:
    """阶段 3:把 in-flight 任务排干,直到归零或到达 deadline。

    优先调用 ``dispatcher.shutdown(timeout)``(对齐 Java
    ``TaskDispatcher.shutdown``);若 dispatcher 未暴露 ``shutdown``,降级
    为轮询 ``in_flight_count()``。
    """
    dispatcher = getattr(client, "dispatcher", None)
    if dispatcher is None:
        return
    shutdown = getattr(dispatcher, "shutdown", None)
    if shutdown is not None:
        budget = max(0.0, _remaining(deadline))
        try:
            await asyncio.wait_for(shutdown(budget), timeout=max(budget, 0.001))
        except TimeoutError:
            ids_fn = getattr(dispatcher, "in_flight_task_ids", None)
            try:
                ids = list(ids_fn()) if ids_fn is not None else []
            except Exception:
                ids = []
            logger.warning(
                "stop_with_timeout: dispatcher.shutdown overran %.3fs; leftover ids=%s",
                budget,
                ids,
            )
        except Exception as exc:
            logger.warning("stop_with_timeout: dispatcher.shutdown raised %s; continuing", exc)
        return
    in_flight_count = getattr(dispatcher, "in_flight_count", None)
    if in_flight_count is None:
        return

    while True:
        try:
            n = int(in_flight_count())
        except Exception as exc:
            logger.warning("stop_with_timeout: in_flight_count() raised %s; aborting drain", exc)
            return
        if n <= 0:
            return
        if _remaining(deadline) <= 0:
            # 超时:把残留任务 ID 打到 WARN,便于运维侧关联排查。
            ids_fn = getattr(dispatcher, "in_flight_task_ids", None)
            try:
                ids = list(ids_fn()) if ids_fn is not None else []
            except Exception as exc:
                logger.warning(
                    "stop_with_timeout: in_flight_task_ids() raised %s; ids unavailable",
                    exc,
                )
                ids = []
            logger.warning(
                "stop_with_timeout: drain timed out with %d in-flight task(s): %s",
                n,
                ids,
            )
            return
        # 取 poll 间隔与剩余预算的较小值,避免越过 deadline。
        await asyncio.sleep(min(_DRAIN_POLL_INTERVAL, _remaining(deadline)))


async def _phase_stop_schedulers(client: Any, budget: float) -> None:
    """阶段 4:在 ``budget`` 秒内停止心跳 / 租约续约调度器。

    依次按多种命名约定查找调度器:
    - ``client.schedulers``:可迭代对象(临时 ``run_worker`` 入口)
    - ``client._heartbeat`` + ``client._lease``:单字段
      (:class:`BatchPlatformClient`)
    """
    schedulers_iter = getattr(client, "schedulers", None)
    schedulers: list[Any] = list(schedulers_iter) if schedulers_iter else []
    for attr in ("_heartbeat", "_lease"):
        s = getattr(client, attr, None)
        if s is not None and s not in schedulers:
            schedulers.append(s)
    if not schedulers:
        return
    # 调度器之间相互独立,并发 stop 即可。
    per_call = max(budget, 0.001)
    coros = []
    targets = []
    for s in schedulers:
        stop = getattr(s, "stop", None)
        if stop is None:
            continue
        coros.append(asyncio.wait_for(stop(), timeout=per_call))
        targets.append(s)
    if not coros:
        return
    results = await asyncio.gather(*coros, return_exceptions=True)
    for s, result in zip(targets, results, strict=False):
        if isinstance(result, Exception):
            logger.warning(
                "stop_with_timeout: scheduler %s stop failed: %s; continuing",
                type(s).__name__,
                result,
            )


async def _phase_deactivate(client: Any) -> None:
    """best-effort POST /deactivate;失败不阻塞 stop。

    与 fixture 12 ``sdkMustNot``"/deactivate HTTP 失败禁止阻塞 stop()
    (log + 继续退出)"对齐。

    按以下命名约定查找 deactivate 入口:
    - ``client.deactivate`` —— 用户自定义方法
    - ``client._http.deactivate`` / ``client.http.deactivate`` ——
      :class:`BatchPlatformClient` 的内部 HTTP client(``_http`` 字段 +
      ``http`` 只读属性)。若可接受参数,以 ``(worker_code, body)`` 调用;
      否则无参调用。
    """
    deactivate = getattr(client, "deactivate", None)
    if deactivate is None:
        http = getattr(client, "_http", None) or getattr(client, "http", None)
        if http is None:
            return
        deactivate = getattr(http, "deactivate", None)
        if deactivate is None:
            return
    try:
        config = getattr(client, "config", None) or getattr(client, "_config", None)
        if config is not None:
            worker_code = getattr(config, "worker_code", "")
            tenant_id = getattr(config, "tenant_id", "")
            await deactivate(worker_code, {"tenantId": tenant_id, "workerCode": worker_code})
        else:
            await deactivate()
    except Exception as exc:
        logger.warning("stop_with_timeout: /deactivate call failed (ignored): %s", exc)


# ASYNC109 说明:本函数确实接受 ``timeout`` 参数,这正是上面所述的"多
# 阶段预算切片"合约。``asyncio.timeout`` 会取消整段 coroutine,这反而会破
# 坏优雅停机语义(即便 drain 超时,/deactivate 也必须尝试一次)。在调用点
# 抑制该 lint。
async def stop_with_timeout(
    client: Any,
    timeout: float,  # noqa: ASYNC109
) -> None:
    """在 ``timeout`` 总预算内分阶段停机 ``client``。

    阶段切分见模块顶部 docstring。任何阶段超时都仅 WARN 不抛错 —— 函数
    总是会在 ``timeout`` 秒(加上最终 /deactivate 的少量抖动,因为合约要
    求即使预算耗尽也必须 attempt 一次 /deactivate)内返回。

    :param client: :class:`BatchPlatformClient` 实例。这里 duck-typing,
        预期属性见模块 docstring;形参类型为 :class:`Any` 而非具体类,
        以打破对 client 模块的循环依赖。
    :param timeout: 总 wall-clock 预算(秒)。必须为正;小于 0.01 的值
        会被钳制为 0.01s。
    """
    if timeout <= 0:
        raise ValueError(f"stop_with_timeout: timeout must be positive, got {timeout!r}")
    timeout = max(timeout, 0.01)

    loop = asyncio.get_event_loop()
    start = loop.time()
    overall_deadline = start + timeout

    logger.info("stop_with_timeout: starting phased shutdown (timeout=%.3fs)", timeout)

    # 阶段 1:翻 draining 标志 —— 同步操作,零预算。
    dispatcher = getattr(client, "dispatcher", None)
    if dispatcher is not None:
        start_draining = getattr(dispatcher, "start_draining", None)
        if callable(start_draining):
            try:
                result = start_draining()
                if asyncio.iscoroutine(result):
                    await result
            except Exception as exc:
                logger.warning("stop_with_timeout: start_draining() failed: %s", exc)
        else:
            # 找不到方法时回退到已知的字段名直接置位。
            with contextlib.suppress(Exception):
                dispatcher._draining = True

    # 阶段 2:Kafka 消费者。
    kafka_budget = _remaining(overall_deadline) * _KAFKA_FRACTION
    await _phase_stop_kafka(client, kafka_budget)

    # 阶段 3:dispatcher drain。deadline = now + (剩余 * 60%)。
    remaining = _remaining(overall_deadline)
    drain_deadline = loop.time() + remaining * _DRAIN_FRACTION / (
        _DRAIN_FRACTION + _SCHEDULER_FRACTION
    )
    await _phase_drain_in_flight(client, drain_deadline)

    # 阶段 4:调度器。
    sched_budget = _remaining(overall_deadline)
    await _phase_stop_schedulers(client, sched_budget)

    # 阶段 5(预算外):/deactivate —— best-effort。
    await _phase_deactivate(client)

    elapsed = loop.time() - start
    logger.info("stop_with_timeout: completed in %.3fs (budget=%.3fs)", elapsed, timeout)
