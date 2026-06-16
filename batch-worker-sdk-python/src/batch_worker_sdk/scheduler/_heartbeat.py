"""异步心跳调度器。

对应 Java ``com.example.batch.sdk.scheduler.HeartbeatScheduler``,作为长生
命周期的 ``asyncio.Task`` 由
:class:`~batch_worker_sdk.client.client.BatchPlatformClient.start` 启动。

与 Java 端保持的关键不变量:

- **HTTP 失败永不杀死循环。** Java 端用 ``try { ... } catch (Throwable) {
  log.warn }`` 包整个 tick;Python 端同样吸收任何异常并 WARN,空窗期由
  orch 端的"missed-heartbeat 阈值"处理。
- **调度器响应 ``nextHeartbeatHint``**(PR #251)。orch 可上调或下调下一
  tick 间隔;本地把 hint 钳制到 ``[1s, baseline * 10]``,避免配置错误的
  orch 让 worker 100ms 风暴或 1h 饥饿。

解耦说明:调度器只依赖一个轻量级的 :class:`DispatcherLike` Protocol,而非
具体 ``TaskDispatcher`` 实现。这样既能让 mypy strict 在两边分别迭代时通过,
也方便测试代码注入小型假实现而无需引入 ``aiokafka`` 等重型依赖。
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from datetime import UTC, datetime
from typing import Any, Final, Protocol, runtime_checkable

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import PlatformError
from batch_worker_sdk.internal import _fingerprint, _progress
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.scheduler._directive import ParsedDirective, parse_directive

logger = logging.getLogger(__name__)


# ─── 与 Java HeartbeatScheduler 一致的常量 ────────────────────────────
# 对应 Java MIN_HINT_MS=1000 和 MAX_HINT_MULTIPLIER=10。public 名字供单测
# 直接 import,锁定 [MIN, baseline * MAX] 钳制契约,防止常量被误改后心跳
# 风暴(< 1s)或心跳饥饿(> baseline * 10)回归。
MIN_HEARTBEAT_INTERVAL_S: Final[float] = 1.0
MAX_HEARTBEAT_HINT_MULTIPLIER: Final[int] = 10


@runtime_checkable
class DispatcherLike(Protocol):
    """调度器实际需要的 ``TaskDispatcher`` 方法子集。

    ``TaskDispatcher`` 天然实现此 Protocol;这里显式声明是为了让调度器
    与具体 dispatcher 解耦,便于独立单元测试。
    """

    def in_flight_count(self) -> int: ...
    def in_flight_task_ids(self) -> set[int]: ...
    def apply_platform_directive(self, directive: Any) -> None: ...
    def mark_cancel_requested(self, task_id: int, reason: str) -> None: ...




def _utc_now_iso() -> str:
    """RFC 3339 时间戳,与 Java ``Instant.now().toString()`` 字符串一致。"""
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


class HeartbeatScheduler:
    """周期性心跳,允许 orch 端动态调节节流。

    Java 对应类:``HeartbeatScheduler``(固定延时调度 + ``applyHeartbeatHint``
    动态节流)。

    Args:
        config: 已校验的 SDK 配置(提供 ``heartbeat_interval``)。
        http: 已建立连接的 :class:`PlatformHttpClient`。
        dispatcher: 满足 :class:`DispatcherLike` 的对象 —— 心跳 body 需要
            ``in_flight_count``,解析出的 directive 会回灌至
            ``apply_platform_directive``。
    """

    def __init__(
        self,
        config: BatchPlatformClientConfig,
        http: PlatformHttpClient,
        dispatcher: DispatcherLike,
        *,
        worker_group: str = "sdk-self-hosted",
        capability_tags: list[str] | None = None,
    ) -> None:
        self._config = config
        self._http = http
        self._dispatcher = dispatcher
        # P0-4:心跳 body 必须含 worker_group / host_name / host_ip / process_id /
        # capability_tags / build_id,否则平台 heartbeat 路径会刷新 worker_registry
        # 把 register 时落下的运维元数据列覆盖为 NULL。这些值在启动期一次性采集,
        # 整个进程生命周期复用,无需每次 tick 重新解析 hostname / pid。
        self._worker_group: str = worker_group
        self._capability_tags: list[str] = list(capability_tags or [])
        self._baseline_s: float = config.heartbeat_interval.total_seconds()
        self._next_interval_s: float = self._baseline_s
        self._task: asyncio.Task[None] | None = None
        self._stop_event: asyncio.Event = asyncio.Event()

    # ─── 可观察状态(测试 + 诊断) ────────────────────────────────────

    @property
    def current_interval_s(self) -> float:
        """当前生效的心跳间隔(经 hint 钳制后的秒数)。"""
        return self._next_interval_s

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    # ─── 生命周期 ────────────────────────────────────────────────────

    async def start(self) -> None:
        """以后台任务形式启动心跳循环。

        幂等:重复调用 ``start()`` 直接返回(DEBUG 记录)。
        """
        if self.running:
            logger.debug("HeartbeatScheduler already running, ignoring start()")
            return
        self._stop_event.clear()
        self._task = asyncio.create_task(self._run_loop(), name="sdk-heartbeat")
        logger.info("HeartbeatScheduler started: interval=%.3fs", self._baseline_s)

    async def stop(self) -> None:
        """请求停止并等待循环退出,可重复调用。"""
        if self._task is None:
            return
        self._stop_event.set()
        try:
            await asyncio.wait_for(self._task, timeout=self._baseline_s + 1.0)
        except TimeoutError:
            logger.warning("HeartbeatScheduler did not exit cleanly; cancelling")
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._task
        self._task = None
        logger.info("HeartbeatScheduler stopped")

    # ─── 单次 tick(暴露给单测使用) ─────────────────────────────────

    async def tick(self) -> ParsedDirective | None:
        """执行一次心跳并应用响应中的 directive。

        返回解析后的 directive(测试 / 可观测性场景使用);心跳 HTTP 失败
        时返回 ``None``。

        异常被吸收:调用方是循环本体,不能让它挂掉。
        """
        body: dict[str, Any] = {
            "tenantId": self._config.tenant_id,
            "workerCode": self._config.worker_code,
            "workerGroup": self._worker_group,
            "status": "RUNNING",
            "heartbeatAt": _utc_now_iso(),
            "currentLoad": self._dispatcher.in_flight_count(),
            "capabilityTags": self._capability_tags,
        }
        # 指纹字段尽力而为:None 不写入,避免把 register 时落下的字段静默清空
        # (平台 jackson NON_NULL 兼容,但 Python 这边自己 elide 也更清晰)。
        host_name = _fingerprint.host_name()
        if host_name is not None:
            body["hostName"] = host_name
        host_ip = _fingerprint.host_ip()
        if host_ip is not None:
            body["hostIp"] = host_ip
        body["processId"] = _fingerprint.process_id()
        if self._config.build_id:
            body["buildId"] = self._config.build_id
        # 2026-06-03 docs/design/pipeline-stage-progress-display.md:流式 stage 行级进度上报。
        # Python SDK 用 _progress 模块的 sink(LOAD/GENERATE handler 调 publish),tick 时读最新值。
        rows = _progress.current_rows_processed()
        if rows is not None:
            body["rowsProcessed"] = rows
        total = _progress.current_total_rows_hint()
        if total is not None:
            body["totalRowsHint"] = total
        try:
            resp = await self._http.heartbeat(self._config.worker_code, body)
        except PlatformError as ex:
            # wire-protocol §B:orch 端 missed-heartbeat 阈值能容忍少量丢失,
            # 这里仅 WARN,让循环睡完间隔后再试。
            logger.warning("heartbeat failed: %s", ex)
            return None
        except Exception as ex:
            logger.warning("heartbeat unexpected error: %s", ex)
            return None

        directive = parse_directive(resp)
        try:
            self._dispatcher.apply_platform_directive(directive)
        except Exception as ex:
            logger.warning("apply_platform_directive failed: %s", ex)

        # ADR-035 §11:hint 驱动的动态节流。
        if directive.next_heartbeat_hint is not None:
            self._apply_hint(directive.next_heartbeat_hint.total_seconds())
        return directive

    # ─── 内部实现 ────────────────────────────────────────────────────

    async def _run_loop(self) -> None:
        """固定延时调度 —— 每轮按 **当前** 间隔睡眠。

        对齐 Java 的 ``scheduleWithFixedDelay``:下一 tick 时刻以上一 tick
        **完成** 为锚,而非开始,因此缓慢的心跳不会堆积或对平台造成 stampede。
        """
        try:
            while not self._stop_event.is_set():
                try:
                    await asyncio.wait_for(
                        self._stop_event.wait(),
                        timeout=self._next_interval_s,
                    )
                    # event 已置位 → 退出循环
                    return
                except TimeoutError:
                    pass
                await self.tick()
        except asyncio.CancelledError:
            raise

    def _apply_hint(self, hint_s: float) -> None:
        """对 orch 下发的心跳间隔提示做钳制并应用。

        - ``< 1s`` → 1s(防止风暴)
        - ``> 10 * baseline`` → 10 * baseline(防止饥饿)
        - 与当前值相同 → 不动
        """
        max_s = self._baseline_s * MAX_HEARTBEAT_HINT_MULTIPLIER
        clamped = max(MIN_HEARTBEAT_INTERVAL_S, min(max_s, hint_s))
        if clamped == self._next_interval_s:
            return
        logger.info(
            "HeartbeatScheduler re-paced by orch hint: %.3fs -> %.3fs (raw=%.3fs, baseline=%.3fs)",
            self._next_interval_s,
            clamped,
            hint_s,
            self._baseline_s,
        )
        self._next_interval_s = clamped
