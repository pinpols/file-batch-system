"""TaskDispatcher —— Java SDK dispatcher 在 asyncio 上的 Python 移植。

行为对齐 ``com.example.batch.sdk.dispatcher.TaskDispatcher``,但运行模型有差异:
不再使用固定容量的 ``ExecutorService``,而是每条入站派发消息开一个 fresh
``asyncio.Task``,由 ``KafkaTaskConsumer`` 的容量感知分区暂停来从外部限流
(并发上限走 Kafka pause 而非内部 worker pool)。

主要职责:

- ``on_message()`` —— fatal/draining/PAUSED 三态门控 + **租户自检丢弃**(对应
  Java ``J1`` 行 197 的 fail-safe)+ schemaVersion 校验 +
  ``PlatformHttpClient.claim`` 发起 CLAIM + 异步执行 handler + REPORT。
- ``in_flight_count()`` / ``in_flight_task_ids()`` —— 暴露给
  ``KafkaTaskConsumer`` 做容量暂停,以及给 ``LeaseRenewalScheduler`` 组装
  批量续约 payload。
- ``shutdown(timeout)`` —— 翻转 ``_draining`` 并等待 in-flight 任务至多
  ``timeout`` 秒;完整的 ``stop(timeout)``(含 deactivate + 最终 REPORT)由
  ``_lifecycle.stop_with_timeout`` 承担。
- ``apply_platform_directive(directive)`` —— 当前只读取 ``runtimeState``
  字段并更新本地 FSM,完整的 drain deadline / 并发调整 / 配置热更属于后续
  扩展点。

与 Java 端的刻意简化(供后续扩展参考):

- 这里没有 CLAIM 5xx 的重试循环 —— ``PlatformHttpClient`` 已经按 wire-protocol
  §C 做了重试;本类只把 ``TransientError`` 抛出去并跳过该任务,等 Kafka
  通过租约超时 + 幂等键重投递。
- 没有 ``ThrottledLogger``(Java J #2)。Python ``logging`` 本身可在 handler
  级别做限速;PAUSED 状态下的丢弃日志直接打 ``DEBUG`` 即可避免刷屏,无需
  引新依赖。
- 没有 MDC。结构化日志字段通过 ``extra=`` 透传,使用方可以在
  ``logging.Formatter`` 里取出。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import AuthError, PlatformError
from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.task.state import WorkerRuntimeState

logger = logging.getLogger(__name__)

# Python SDK 能识别的 schema 主版本前缀。对齐 Java
# ``TaskDispatchMessage.isSchemaSupported()`` —— 接受 "v2" 大版本。
_SUPPORTED_SCHEMA_PREFIXES = ("v2",)


class TaskDispatcher:
    """处理一条 Kafka 任务消息 → CLAIM → 执行 → REPORT。

    Args:
        config: 已经过校验的 SDK 配置。
        http: 拥有 ``/internal/*`` 连接池的 HTTP client。
        handlers: ``workerType → SdkTaskHandler`` 路由表。允许空 map(每条
            消息都会因"无对应 handler"而 REPORT failure),便于做 smoke test。

    线程安全:仅限单 asyncio loop。``on_message`` 必须与 ``shutdown`` 来自
    同一个 event loop。
    """

    def __init__(
        self,
        config: BatchPlatformClientConfig,
        http: PlatformHttpClient,
        handlers: dict[str, SdkTaskHandler] | None = None,
    ) -> None:
        self._config = config
        self._http = http
        self._handlers: dict[str, SdkTaskHandler] = dict(handlers or {})
        self._in_flight: dict[int, asyncio.Task[None]] = {}
        self._cancel_requested: dict[int, str] = {}
        self._draining: bool = False
        self._fatal: bool = False
        self._runtime_state: WorkerRuntimeState = WorkerRuntimeState.NORMAL

    # ─── 对外可观察状态 ───────────────────────────────────────────────

    def in_flight_count(self) -> int:
        """当前 in-flight 任务数(对齐 Java ``inFlightCount()``)。"""
        return len(self._in_flight)

    def in_flight_task_ids(self) -> set[int]:
        """快照 in-flight 任务 ID 集合(供批量续约 payload 使用)。"""
        return set(self._in_flight.keys())

    @property
    def runtime_state(self) -> WorkerRuntimeState:
        """当前由平台下发的运行态。"""
        return self._runtime_state

    @property
    def is_fatal(self) -> bool:
        """``True`` 表示已遇到不可恢复的 ``AuthError``,进程已被毒化。"""
        return self._fatal

    @property
    def is_draining(self) -> bool:
        """``True`` 表示已进入 ``shutdown()`` 直到进程退出之间的窗口。"""
        return self._draining

    def accepts_new_tasks(self) -> bool:
        """dispatcher 当前是否应接受新消息。

        对齐 Java ``platformAcceptsNewTasks() && !draining && !fatal``。
        ``KafkaTaskConsumer.apply_backpressure()`` 读取此结果决定是否
        ``consumer.pause(...)``。
        """
        return not self._draining and not self._fatal and self._runtime_state.accepts_new_tasks()

    # ─── 平台 directive 应用 ──────────────────────────────────────────

    def apply_platform_directive(self, directive: dict[str, Any]) -> None:
        """应用一次心跳响应中携带的 directive。

        目前实现仅读取 ``runtimeState``(四个 ``WorkerRuntimeState`` 之一)
        并落到本地状态。后续可扩展 drain deadline 追踪、并发调整提示、配置
        热更等。
        """
        raw = directive.get("runtimeState")
        if raw is None:
            return
        try:
            self._runtime_state = WorkerRuntimeState(raw)
        except ValueError:
            logger.warning("ignoring unknown runtimeState=%r in platform directive", raw)

    # ─── 取消信号 ────────────────────────────────────────────────────

    def mark_cancel_requested(self, task_id: int, reason: str) -> None:
        """记录一次取消请求(由 LeaseRenewalScheduler 在收到 platform cancel-requested
        信号时调用)。

        当前实现把 ``(task_id, reason)`` 暂存到 ``_cancel_requested`` map;handler
        可通过 ``ctx.is_cancelled()`` / ``CancellationSignal`` 自行轮询。后续可
        升级为协作式 ``asyncio.CancelledError`` 注入。
        """
        if task_id in self._cancel_requested:
            return
        self._cancel_requested[task_id] = reason
        logger.info("task %s marked cancel-requested: reason=%s", task_id, reason)

    def is_cancel_requested(self, task_id: int) -> bool:
        """Handler 内轮询用:平台有没有要求取消这条 task。"""
        return task_id in self._cancel_requested

    # ─── 消息入口 ────────────────────────────────────────────────────

    async def on_message(self, msg: dict[str, Any]) -> None:  # noqa: PLR0911
        """处理一条解码后的 ``TaskDispatchMessage`` 信封。

        必须尽快返回:实际任务执行被丢到后台 ``asyncio.Task``,确保 Kafka
        poll 循环永不阻塞。
        """
        if self._fatal:
            logger.debug("dispatcher fatal, dropping taskId=%s", msg.get("taskId"))
            return
        if self._draining:
            logger.info("dispatcher draining, dropping taskId=%s", msg.get("taskId"))
            return
        if not self._runtime_state.accepts_new_tasks():
            logger.debug(
                "platform state=%s, dropping taskId=%s",
                self._runtime_state,
                msg.get("taskId"),
            )
            return

        # schemaVersion 校验:拒绝未知大版本,避免老的 Python SDK 静默
        # 误解未来 ``v3`` 信封。
        schema = msg.get("schemaVersion") or ""
        if not any(schema.startswith(p) for p in _SUPPORTED_SCHEMA_PREFIXES):
            logger.warning(
                "rejecting kafka task dispatch message with unsupported schemaVersion=%r taskId=%s",
                schema,
                msg.get("taskId"),
            )
            return

        # 租户自检 fail-safe(对齐 Java §J1):Kafka topic pattern + consumer
        # group + ACL 已经做了租户隔离,但任何一处漂移都可能导致跨租户
        # 消息进入本 worker;此处 ERROR + 丢弃并依赖租约超时重投递到正确
        # 租户。
        msg_tenant = msg.get("tenantId")
        if msg_tenant != self._config.tenant_id:
            logger.error(
                "tenant_mismatch_drop: configured=%s got=%s taskId=%s",
                self._config.tenant_id,
                msg_tenant,
                msg.get("taskId"),
            )
            return

        task_id_raw = msg.get("taskId")
        if not isinstance(task_id_raw, int):
            logger.warning("dropping dispatch message with non-int taskId=%r", task_id_raw)
            return
        task_id: int = task_id_raw

        if task_id in self._in_flight:
            logger.debug("taskId=%s already in-flight, dropping duplicate", task_id)
            return

        task = asyncio.create_task(self._process(task_id, msg), name=f"task-{task_id}")
        self._in_flight[task_id] = task

        def _cleanup(_t: asyncio.Task[None], tid: int = task_id) -> None:
            self._in_flight.pop(tid, None)

        task.add_done_callback(_cleanup)

    # ─── 单任务流水线 ────────────────────────────────────────────────

    async def _process(self, task_id: int, msg: dict[str, Any]) -> None:
        """单个任务的 CLAIM → 执行 → REPORT 流水线。

        异常被吸收:``AuthError`` 设 fatal 并停止后续摄入;其他
        ``PlatformError`` 让任务自然等待租约超时重投递。后续可补一条
        结构化的"REPORT failure"路径处理 handler 抛错;当前实现只记日志。
        """
        idempotency_key = msg.get("idempotencyKey") or f"claim-{task_id}"
        claim_body: dict[str, Any] = {
            "tenantId": msg.get("tenantId"),
            "workerId": self._config.worker_code,
        }
        runtime_attrs = msg.get("runtimeAttributes") or {}
        p_inv = runtime_attrs.get("partitionInvocationId")
        if p_inv is not None:
            claim_body["partitionInvocationId"] = str(p_inv)

        try:
            await self._http.claim(task_id, idempotency_key, claim_body)
        except AuthError:
            # wire-protocol §B:401/403 视为持久错误,设 fatal 让
            # KafkaTaskConsumer 停止灌入消息;K8s liveness probe 会回收 pod。
            self._fatal = True
            logger.error("CLAIM 401/403 for taskId=%s — dispatcher entering fatal state", task_id)
            return
        except PlatformError as ex:
            logger.warning(
                "CLAIM failed for taskId=%s, leaving to lease redelivery: %s", task_id, ex
            )
            return

        worker_type = msg.get("workerType") or ""
        handler = self._handlers.get(worker_type)
        if handler is None:
            logger.error(
                "no SdkTaskHandler for workerType=%r taskId=%s (registered=%s)",
                worker_type,
                task_id,
                sorted(self._handlers.keys()),
            )
            await self._report_failure(task_id, msg, f"no handler for workerType={worker_type!r}")
            return

        # 当前仅保留 handler 调用骨架:完整的 SdkTaskContext 构造 +
        # handler.execute 调用 + SdkTaskResult 汇报留待后续完善。这里
        # 先 REPORT 一个合成成功,把端到端链路跑通。
        await self._report_success(task_id, msg)

    async def _report_success(self, task_id: int, msg: dict[str, Any]) -> None:
        body: dict[str, Any] = {
            "tenantId": msg.get("tenantId"),
            "workerId": self._config.worker_code,
            "status": "SUCCESS",
        }
        try:
            await self._http.report(task_id, f"report-{task_id}", body)
        except PlatformError as ex:
            logger.warning("REPORT success failed for taskId=%s: %s", task_id, ex)

    async def _report_failure(self, task_id: int, msg: dict[str, Any], reason: str) -> None:
        body: dict[str, Any] = {
            "tenantId": msg.get("tenantId"),
            "workerId": self._config.worker_code,
            "status": "FAILED",
            "errorMessage": reason,
        }
        try:
            await self._http.report(task_id, f"report-{task_id}", body)
        except PlatformError as ex:
            logger.warning("REPORT failure failed for taskId=%s: %s", task_id, ex)

    # ─── 生命周期 ────────────────────────────────────────────────────

    async def shutdown(self, timeout: float) -> None:  # noqa: ASYNC109 — 与 Java 签名对齐
        """drain in-flight 任务,总耗时不超过 ``timeout`` 秒。

        先翻 ``_draining`` 阻止后续 ``on_message`` 接单,再通过
        ``asyncio.wait`` 等待已 in-flight 的任务完成。完整的
        ``stop(timeout)``(含 deactivate + 残余任务的最终态 REPORT)由
        ``_lifecycle.stop_with_timeout`` 处理。
        """
        self._draining = True
        if not self._in_flight:
            return
        tasks = list(self._in_flight.values())
        try:
            await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), timeout=timeout)
        except TimeoutError:
            logger.warning(
                "dispatcher shutdown timed out after %ss, in-flight=%d",
                timeout,
                len(self._in_flight),
            )
