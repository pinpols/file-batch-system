"""TaskDispatcher —— Java SDK dispatcher 在 asyncio 上的 Python 移植。

行为对齐 ``io.github.pinpols.batch.sdk.dispatcher.TaskDispatcher``,但运行模型有差异:
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
import json
import logging
import re
import uuid
from enum import Enum
from typing import TYPE_CHECKING, Any

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import AuthError, PlatformError
from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.idempotent import SdkIdempotencyStore, wrap_idempotent
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.task.cancellation import CancellationSignal
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult
from batch_worker_sdk.task.state import WorkerRuntimeState

if TYPE_CHECKING:
    from batch_worker_sdk.scheduler._directive import ParsedDirective


def _directive_fields(
    directive: ParsedDirective | dict[str, Any],
) -> tuple[str | None, int | None, list[str]]:
    """从 directive(``ParsedDirective`` 强类型 **或** 原始 ``dict``)抽取
    ``(runtimeState, desiredMaxConcurrent, pausedTaskTypes)``。

    鸭子类型分派,避免 dispatcher → scheduler 的运行时 import:``dict`` 走
    ``.get``;``ParsedDirective`` 走属性。``ParsedDirective.platform_status`` 是
    ``WorkerRuntimeState`` 枚举,取其 ``.value`` 归一成字符串。
    """
    if isinstance(directive, dict):
        raw_state = directive.get("runtimeState")
        state = str(raw_state) if raw_state is not None else None
        desired_raw = directive.get("desiredMaxConcurrent")
        desired = int(desired_raw) if isinstance(desired_raw, int) else None
        paused_raw = directive.get("pausedTaskTypes")
        paused = [str(t) for t in paused_raw] if isinstance(paused_raw, list) else []
        return state, desired, paused
    # ParsedDirective(或任何带这些属性的对象)。
    status = directive.platform_status
    state = status.value if status is not None else None
    return state, directive.desired_max_concurrent, list(directive.paused_task_types or [])


def _new_idempotency_key() -> str:
    """生成一次性 idempotency-key,对齐 Java ``BatchPlatformClient.newIdempotencyKey()``。

    格式 ``sdk-py-<uuid4>``;``sdk-py`` 前缀区分 Python SDK 与 Java SDK
    (``sdk-<uuid4>``),方便平台运维按 grep 前缀定位调用源语言。每次
    CLAIM / REPORT 调用独立 key,符合 wire-protocol §A:5xx 重试同 key
    (由 ``with_retry`` 内部复用),新 outcome 必须新 key,避免平台幂等
    存储回放上一次结果。
    """
    return f"sdk-py-{uuid.uuid4()}"


logger = logging.getLogger(__name__)

# Python SDK 能识别的 schema 主版本 token。对齐 Java
# ``TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS = Set.of("v1", "v2")``。
# 注意:必须按"主版本 token"精确匹配,**不能**用 ``startswith``——后者会把
# ``"v10"`` 误判为受支持(``"v10".startswith("v1") is True``)。
_SUPPORTED_SCHEMA_MAJORS = frozenset({"v1", "v2"})

# 提取 schemaVersion 的前导主版本 token(``^[a-zA-Z0-9]+``):``"v2.1"`` →
# ``"v2"``、``"v10"`` → ``"v10"``、``"v1-beta"`` → ``"v1"``。对齐 Java 端
# 按 major token 比对的语义。
_SCHEMA_MAJOR_RE = re.compile(r"^[a-zA-Z0-9]+")


def _schema_major(schema: str) -> str:
    """取 schemaVersion 的前导主版本 token;无匹配时返回空串。"""
    m = _SCHEMA_MAJOR_RE.match(schema)
    return m.group(0) if m else ""


# 缺字段 / 空白 schemaVersion 的 fallback 主版本,对齐 Java
# ``TaskDispatchMessage.DEFAULT_SCHEMA_VERSION``(老 orchestrator 没填时按
# ``v1`` 解析)。契约 fixture ``16-kafka-schema-version-missing-accept`` 要求
# 缺省必须 accept,不能拒。
DEFAULT_SCHEMA_VERSION = "v1"


class DispatchDisposition(Enum):
    """dispatcher 对单条派单消息的处理决定;``KafkaTaskConsumer`` 据此决定 offset 提交。

    对齐 Java ``TaskDispatcher.DispatchDecision`` + Go ``MessageDisposition``——
    **不能**像旧实现那样无差别 commit。
    """

    #: 已受理(成功开后台 Task / 重复投递);offset 可前移。
    ACCEPTED = "ACCEPTED"
    #: 消息不可恢复(解码失败 / taskId 非法);跳过并提交 offset,避免 poison 卡分区。
    DROP_TERMINAL = "DROP_TERMINAL"
    #: 当前 worker 不应消费(fatal / draining / 平台暂停 / 跨租户 / 未知 schema 大版本);
    #: offset **不前移**,留待平台恢复或 SDK 升级后从原位重投。未知 schema 大版本不
    #: 提交 offset 是 wire-protocol §A 的硬契约(避免按错版本反序列化字段错乱)。
    RETRY_LATER = "RETRY_LATER"


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
        *,
        idempotency_store: SdkIdempotencyStore | None = None,
    ) -> None:
        self._config = config
        self._http = http
        self._handlers: dict[str, SdkTaskHandler] = {
            task_type: wrap_idempotent(handler, idempotency_store)
            for task_type, handler in (handlers or {}).items()
        }
        self._in_flight: dict[int, asyncio.Task[None]] = {}
        # task_id → CancellationSignal,handler 在 ctx 上持有同一引用;
        # LeaseRenewalScheduler 通过 mark_cancel_requested(task_id, reason)
        # 翻其 asyncio.Event。普通 dict;**single-event-loop only;not
        # thread-safe**(与 _in_flight 一致,owner 是 dispatcher 所在的
        # 唯一 asyncio loop)。
        self._cancel_signals: dict[int, CancellationSignal] = {}
        # task_id → partitionInvocationId(可能为 None)。在 CLAIM 时从
        # ``msg.runtimeAttributes`` 提取并缓存,供 LeaseRenewalScheduler 组装
        # renew body 时回读(openapi TaskHeartbeatRequest 要求带上,fixture 10)。
        # 与 ``_cancel_signals`` 同生命周期:on_message 入队即建,done_callback
        # cleanup 时清。**single-event-loop only; not thread-safe**。
        self._partition_invocation_ids: dict[int, str | None] = {}
        self._draining: bool = False
        self._fatal: bool = False
        self._runtime_state: WorkerRuntimeState = WorkerRuntimeState.NORMAL
        # 平台动态压并发(wire-protocol §2.1 desiredMaxConcurrent):None 表示沿用
        # 本地配置 max_concurrent_tasks;>0 时收敛到平台建议值(取 min,不抬高)。
        self._effective_max_concurrent: int | None = None
        # 平台下发的暂停任务类型集合(wire-protocol §2.1 pausedTaskTypes):on_message
        # 收到 workerType ∈ 此集合的消息 → drop 且不提交 offset,平台 unpause 后重投。
        self._paused_task_types: set[str] = set()

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
    def effective_max_concurrent(self) -> int:
        """当前生效的并发上限 = min(本地配置, 平台 desiredMaxConcurrent)。

        平台未下发 / 下发 ``None`` / ``<=0`` 时沿用本地 ``max_concurrent_tasks``;
        下发正值时收敛到其与本地配置的较小者(只下压、不抬高,对齐 fixture 27
        与 Go/Java ``effectiveMaxConcurrent`` 语义)。
        """
        local = self._config.max_concurrent_tasks
        if self._effective_max_concurrent is None:
            return local
        return min(local, self._effective_max_concurrent)

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

    def apply_platform_directive(self, directive: ParsedDirective | dict[str, Any]) -> None:
        """应用一次心跳响应中携带的 directive。

        接受两种形态(向后兼容):

        - :class:`ParsedDirective` —— ``HeartbeatScheduler`` 解析后回灌的强类型
          视图(**生产路径**)。早先这里只认 ``dict``,对 ``ParsedDirective`` 会
          ``AttributeError`` 被心跳循环捕获并抑制,导致心跳下发的 FSM 切换 / 并发收敛
          **永不生效**(directive 覆盖缺口)。
        - ``dict`` —— 直接传原始 directive 块(测试 / 旧调用方)。

        应用两件事:

        1. ``runtimeState`` → 本地四态 FSM(NORMAL/DEGRADED/PAUSED/DRAINING)。
           PAUSED/DRAINING 会让 ``accepts_new_tasks()`` 返回 False,驱动 Kafka
           pause;DEGRADED/NORMAL 继续接单(DEGRADED 不 pause)。
        2. ``desiredMaxConcurrent`` → 收敛 ``effective_max_concurrent``(只下压)。
        3. ``pausedTaskTypes`` → 落 ``_paused_task_types``,``on_message`` 据此对命中
           workerType 的消息做 per-message drop(不提交 offset,平台 unpause 后重投)。
        """
        state_raw, desired, paused = _directive_fields(directive)
        if state_raw is not None:
            try:
                self._runtime_state = WorkerRuntimeState(state_raw)
            except ValueError:
                logger.warning("ignoring unknown runtimeState=%r in platform directive", state_raw)
        # desiredMaxConcurrent:>0 收敛;None / <=0 → 清回本地配置(沿用 03 语义)。
        if desired is not None and desired > 0:
            self._effective_max_concurrent = desired
        elif desired is not None:
            # 显式下发 0 / 负值:视为「撤销收敛」,回落本地配置。
            self._effective_max_concurrent = None
        # pausedTaskTypes:全量替换(每次 directive 携带当前完整暂停集合)。
        self._paused_task_types = set(paused)

    # ─── 取消信号 ────────────────────────────────────────────────────

    def mark_cancel_requested(self, task_id: int, reason: str) -> None:
        """翻转 in-flight task 的 :class:`CancellationSignal`。

        由 :class:`LeaseRenewalScheduler` 在 renew 响应里收到
        ``cancelRequested=true`` 或 lease 被吊销(404/410)时调用。Handler
        在 :class:`SdkTaskContext` 上持有同一份 ``CancellationSignal``,
        在长循环里轮询 ``ctx.cancel_signal.is_cancellation_requested`` 或
        ``await ctx.cancel_signal.wait_cancelled()``。

        对未知 ``task_id``(已完成 / 已 cleanup)只 DEBUG,不抛 —— 续约
        tick 与 handler done callback 之间是 race,容忍单向丢失是正常路径。
        ``CancellationSignal.mark_cancelled`` 本身幂等,重复调用安全。
        """
        signal = self._cancel_signals.get(task_id)
        if signal is not None:
            signal.mark_cancelled()
            logger.info("task %s cancellation marked: %s", task_id, reason)
        else:
            logger.debug(
                "mark_cancel_requested for unknown task_id=%s (already done?) reason=%s",
                task_id,
                reason,
            )

    def is_cancel_requested(self, task_id: int) -> bool:
        """Handler 内轮询用:平台有没有要求取消这条 task。"""
        signal = self._cancel_signals.get(task_id)
        return signal is not None and signal.is_cancellation_requested

    def partition_invocation_id(self, task_id: int) -> str | None:
        """返回 CLAIM 时缓存的 ``partitionInvocationId``(无则 ``None``)。

        供 :class:`LeaseRenewalScheduler._renew_one` 组装 renew body —— openapi
        ``TaskHeartbeatRequest`` 要求续约时回带分区调用 id(fixture 10)。未知
        task_id(已 cleanup / 从未 claim)返回 ``None``。
        """
        return self._partition_invocation_ids.get(task_id)

    # ─── 消息入口 ────────────────────────────────────────────────────

    async def on_message(self, msg: dict[str, Any]) -> DispatchDisposition:  # noqa: PLR0911
        """处理一条解码后的 ``TaskDispatchMessage`` 信封,返回 offset 处置决定。

        必须尽快返回:实际任务执行被丢到后台 ``asyncio.Task``,确保 Kafka
        poll 循环永不阻塞。返回的 :class:`DispatchDisposition` 由
        ``KafkaTaskConsumer`` 据此决定该条 offset 是否提交——**不再无差别
        commit**(对齐 Java/Go 契约)。
        """
        if self._fatal:
            logger.debug("dispatcher fatal, dropping taskId=%s", msg.get("taskId"))
            return DispatchDisposition.RETRY_LATER
        if self._draining:
            logger.info("dispatcher draining, dropping taskId=%s", msg.get("taskId"))
            return DispatchDisposition.RETRY_LATER
        if not self._runtime_state.accepts_new_tasks():
            logger.debug(
                "platform state=%s, dropping taskId=%s",
                self._runtime_state,
                msg.get("taskId"),
            )
            return DispatchDisposition.RETRY_LATER

        # pausedTaskTypes per-message drop(wire-protocol §2.1):workerType 命中平台
        # 暂停集合 → drop 且不提交 offset(RETRY_LATER),平台 unpause 后重投。对齐
        # 决策核 decidePausedTaskType。早于 schema/tenant 校验:暂停期连解析都不必。
        if self._paused_task_types:
            worker_type = msg.get("workerType")
            if isinstance(worker_type, str) and worker_type in self._paused_task_types:
                logger.info(
                    "workerType=%s paused, dropping taskId=%s without commit (redelivered on unpause)",
                    worker_type,
                    msg.get("taskId"),
                )
                return DispatchDisposition.RETRY_LATER

        # schemaVersion 校验:缺字段 / 空白按 v1 解析(对齐 Java + fixture 16),
        # 仅未知大版本(如 v3)才拒;且拒时不提交 offset(§A),避免老 SDK 按错
        # 版本反序列化字段错乱。
        schema = msg.get("schemaVersion")
        major = (
            _schema_major(schema)
            if isinstance(schema, str) and schema.strip()
            else DEFAULT_SCHEMA_VERSION
        )
        if major not in _SUPPORTED_SCHEMA_MAJORS:
            logger.warning(
                "rejecting kafka task dispatch message with unsupported schemaVersion=%r taskId=%s"
                " (offset withheld per wire-protocol §A; upgrade SDK)",
                schema,
                msg.get("taskId"),
            )
            return DispatchDisposition.RETRY_LATER

        # 租户自检 fail-safe(对齐 Java §J1 / Go DROPPED_FOREIGN_TENANT):Kafka topic
        # pattern + consumer group + ACL 已做租户隔离,但任何一处漂移都可能让跨租户
        # 消息进入本 worker;此处 ERROR + 不提交 offset,依赖租约超时重投递到正确租户。
        msg_tenant = msg.get("tenantId")
        if msg_tenant != self._config.tenant_id:
            logger.error(
                "tenant_mismatch_drop: configured=%s got=%s taskId=%s (offset withheld)",
                self._config.tenant_id,
                msg_tenant,
                msg.get("taskId"),
            )
            return DispatchDisposition.RETRY_LATER

        task_id_raw = msg.get("taskId")
        if not isinstance(task_id_raw, int):
            logger.warning("dropping dispatch message with non-int taskId=%r", task_id_raw)
            return DispatchDisposition.DROP_TERMINAL
        task_id: int = task_id_raw

        if task_id in self._in_flight:
            logger.debug("taskId=%s already in-flight, committing duplicate", task_id)
            return DispatchDisposition.ACCEPTED

        # 先建 CancellationSignal:_process 未来构造 SdkTaskContext 时会从
        # ``_cancel_signals[task_id]`` 取同一份 signal 注入 ctx;
        # LeaseRenewalScheduler 同步翻其 event,handler 通过 ctx 拿到 True。
        self._cancel_signals[task_id] = CancellationSignal()

        task = asyncio.create_task(self._process(task_id, msg), name=f"task-{task_id}")
        self._in_flight[task_id] = task

        def _cleanup(_t: asyncio.Task[None], tid: int = task_id) -> None:
            self._in_flight.pop(tid, None)
            self._cancel_signals.pop(tid, None)
            self._partition_invocation_ids.pop(tid, None)

        task.add_done_callback(_cleanup)
        return DispatchDisposition.ACCEPTED

    # ─── 单任务流水线 ────────────────────────────────────────────────

    async def _process(self, task_id: int, msg: dict[str, Any]) -> None:
        """单个任务的 CLAIM → 执行 → REPORT 流水线。

        异常被吸收:``AuthError`` 设 fatal 并停止后续摄入;其他
        ``PlatformError``(CLAIM 阶段)让任务自然等待租约超时重投递。
        handler 抛错走 REPORT failure 路径(不让异常从后台 asyncio.Task
        泄漏)。CLAIM 命中 409(已被自己/他人 claim)按 wire-protocol §B 视
        为幂等成功:**直接返回**,既不执行 handler 也不 REPORT(否则会污染
        平台的 success=false 计数器,违反 fixture 08)。
        """
        # wire-protocol §A:每次写操作独立 UUID,5xx 重试由 with_retry 内部
        # 复用同 key。若上游 kafka msg 显式带 idempotencyKey 则尊重之(平台
        # 当前不下发,但为向后兼容保留)。
        idem_claim = msg.get("idempotencyKey") or _new_idempotency_key()
        claim_body: dict[str, Any] = {
            "tenantId": msg.get("tenantId"),
            "workerId": self._config.worker_code,
        }
        runtime_attrs = msg.get("runtimeAttributes") or {}
        p_inv_raw = runtime_attrs.get("partitionInvocationId")
        p_inv = str(p_inv_raw) if p_inv_raw is not None else None
        # 在 CLAIM 时缓存 partitionInvocationId,供 LeaseRenewalScheduler 组装
        # renew body(openapi TaskHeartbeatRequest / fixture 10)。
        self._partition_invocation_ids[task_id] = p_inv
        if p_inv is not None:
            claim_body["partitionInvocationId"] = p_inv

        try:
            _claim_resp, status = await self._http.claim_status(task_id, idem_claim, claim_body)
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

        if status == 409:
            # 幂等已被 claim(Kafka at-least-once 重投 / 对端 worker 抢先)。
            # 按 fixture 08:INFO 记录,提交 offset,**不** 执行 handler、
            # **不** REPORT。
            logger.info("task %s already claimed by peer (HTTP 409); skipping execution", task_id)
            return

        # 路由键 = 平台 JSON 字段 `workerType`(v2);v1 旧名 `taskType` 经回退兼容,与 Java
        # @JsonAlias("taskType") / Rust serde alias 跨语言对齐(防漂移)。平台 v2 已只发 workerType,
        # 此回退是 belt-and-suspenders;守护见 test_dispatcher 的 workerType→handler 绑定用例。
        worker_type = msg.get("workerType") or msg.get("taskType") or ""
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

        # 构造执行上下文并调用 handler。handler.execute 是 async,直接 await;
        # 防御性地兼容返回非 coroutine 的实现(同步 handler)。
        ctx = self._build_context(task_id, msg, worker_type, runtime_attrs)
        try:
            raw = handler.execute(ctx)
            result: Any = await raw if asyncio.iscoroutine(raw) else raw
        except Exception as ex:
            # handler 抛错:REPORT failure(而不是让异常从后台 asyncio.Task
            # 泄漏,那样只会进 done_callback 后被吞)。
            logger.exception("handler for taskId=%s raised; reporting failure", task_id)
            await self._report_failure(task_id, msg, f"handler error: {ex}")
            return

        if not isinstance(result, SdkTaskResult):
            logger.error(
                "handler for workerType=%r taskId=%s returned %r (expected SdkTaskResult)",
                worker_type,
                task_id,
                type(result).__name__,
            )
            await self._report_failure(task_id, msg, "handler returned non-SdkTaskResult")
            return

        if result.success:
            await self._report_success(task_id, msg, result)
        else:
            reason = result.message or "handler reported failure"
            await self._report_failure(task_id, msg, reason, result=result)

    def _build_context(
        self,
        task_id: int,
        msg: dict[str, Any],
        worker_type: str,
        runtime_attrs: dict[str, Any],
    ) -> SdkTaskContext:
        """从 Kafka 派发信封物化 :class:`SdkTaskContext`。

        ``cancel_signal`` 取 on_message 入队时建好的同一份引用,使
        LeaseRenewalScheduler 的取消信号能被 handler 通过 ctx 观察到。
        """
        return SdkTaskContext(
            tenant_id=str(msg.get("tenantId") or self._config.tenant_id),
            task_id=task_id,
            worker_code=self._config.worker_code,
            task_type=worker_type,
            parameters=dict(msg.get("parameters") or {}),
            runtime_attributes=dict(runtime_attrs),
            cancel_signal=self._cancel_signals.get(task_id),
        )

    async def _report_success(
        self,
        task_id: int,
        msg: dict[str, Any],
        result: SdkTaskResult | None = None,
    ) -> None:
        # P0-1:对齐平台 ``TaskExecutionReportDto`` —— success(bool) 字段,
        # 不是 status(string)。Jackson @JsonIgnoreProperties(ignoreUnknown=true)
        # 会静默丢弃未知字段,旧版 status="SUCCESS" 会导致 success 默认 false,
        # 平台把每条 Python 任务都判为失败。
        body: dict[str, Any] = {
            "taskId": task_id,
            "tenantId": msg.get("tenantId"),
            "workerId": self._config.worker_code,
            "success": True,
        }
        if result is not None:
            if result.output:
                body["outputs"] = dict(result.output)
            # result_summary 是平台 jsonb 列(#{resultSummary}::jsonb),必须是合法 JSON,
            # 不能是裸人读串(否则 invalid input syntax for type json → report 500)。发
            # {code,message} JSON 对象,对齐内建 worker DefaultTaskExecutionWrapper 的契约。
            body["resultSummary"] = json.dumps({"code": "SUCCESS", "message": result.message or ""})
        self._attach_report_meta(body, msg)
        try:
            await self._http.report(task_id, _new_idempotency_key(), body)
        except PlatformError as ex:
            logger.warning("REPORT success failed for taskId=%s: %s", task_id, ex)

    async def _report_failure(
        self,
        task_id: int,
        msg: dict[str, Any],
        reason: str,
        result: SdkTaskResult | None = None,
    ) -> None:
        # P0-1:失败侧字段:success=false + resultSummary(自由文本)+
        # errorCode(机器可读分类)。已废弃 errorMessage,平台读不到。
        # handler 通过 SdkTaskResult.fail(code, ...) 给出的 errorCode/errorClass
        # 放在 result.output;无 result(no-handler / handler 抛错)回落到
        # SdkDispatchError。
        error_code = "SdkDispatchError"
        outputs: dict[str, Any] = {}
        if result is not None:
            outputs = dict(result.output)
            ec = outputs.get("errorCode")
            if isinstance(ec, str) and ec:
                error_code = ec
        body: dict[str, Any] = {
            "taskId": task_id,
            "tenantId": msg.get("tenantId"),
            "workerId": self._config.worker_code,
            "success": False,
            "message": reason,
            # jsonb 列:发 {code,message} JSON 对象(见 success 路径同理)。
            "resultSummary": json.dumps({"code": error_code, "message": reason}),
            "errorCode": error_code,
        }
        if outputs:
            body["outputs"] = outputs
        self._attach_report_meta(body, msg)
        try:
            await self._http.report(task_id, _new_idempotency_key(), body)
        except PlatformError as ex:
            logger.warning("REPORT failure failed for taskId=%s: %s", task_id, ex)

    def _attach_report_meta(self, body: dict[str, Any], msg: dict[str, Any]) -> None:
        """给 REPORT body 补 traceId / partitionInvocationId(若存在)。"""
        trace_id = msg.get("traceId")
        if trace_id:
            body["traceId"] = trace_id
        runtime_attrs = msg.get("runtimeAttributes") or {}
        p_inv = runtime_attrs.get("partitionInvocationId")
        if p_inv is not None:
            body["partitionInvocationId"] = str(p_inv)

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
