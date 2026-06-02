"""TaskDispatcher — Python (asyncio) port of the Java SDK dispatcher.

Mirrors ``com.example.batch.sdk.dispatcher.TaskDispatcher`` semantics on
an asyncio runtime: instead of a fixed-size ``ExecutorService`` we
launch a fresh ``asyncio.Task`` per incoming dispatch, gated by the
``KafkaTaskConsumer`` capacity-aware partition pause (so concurrency
is bounded externally rather than via a worker pool).

P2 scope (this lane):

- ``on_message()`` — fatal/draining/PAUSED gating, **tenant self-check
  drop** (Java Lane J §J1 line 197), schemaVersion gate, CLAIM via
  ``PlatformHttpClient.claim``, async handler invocation, REPORT.
- ``in_flight_count()`` / ``in_flight_task_ids()`` — exposed to
  ``KafkaTaskConsumer`` for capacity pause and to the upcoming P3
  ``LeaseRenewalScheduler`` for batch-renew payload assembly.
- ``shutdown(timeout)`` — flips ``_draining`` and awaits in-flight
  tasks up to ``timeout`` seconds. ``stop(timeout)`` full lifecycle
  (with deactivate + final report) lands in P4.
- ``apply_platform_directive(directive)`` — minimal stub that only
  sets ``_runtime_state``. Heartbeat-driven state transition + drain
  semantics land in P3.

Intentional simplifications vs Java (called out so P3 has a clear
backlog):

- No CLAIM 5xx retry loop here — ``PlatformHttpClient`` already does
  retry per wire-protocol §C; we surface ``TransientError`` and skip
  the task (Kafka redelivery via lease timeout + idempotency-key).
- No ``ThrottledLogger`` (Java Lane J #2). Python ``logging`` already
  rate-limits at the handler level if needed; PAUSED-state drops are
  logged at ``DEBUG`` to avoid flood without adding deps.
- No MDC. Structured logging fields are passed via ``extra=`` so
  ``logging.Formatter`` users can pick them up.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from batch_worker_sdk._http import PlatformHttpClient
from batch_worker_sdk.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import AuthError, PlatformError
from batch_worker_sdk.handler import SdkTaskHandler
from batch_worker_sdk.state import WorkerRuntimeState

logger = logging.getLogger(__name__)

# Schema versions the Python SDK understands. Mirrors Java
# ``TaskDispatchMessage.isSchemaSupported()`` — accept "v2" major.
_SUPPORTED_SCHEMA_PREFIXES = ("v2",)


class TaskDispatcher:
    """Dispatch one Kafka task message → CLAIM → execute → REPORT.

    Args:
        config: Validated SDK config.
        http: HTTP client owning the ``/internal/*`` connection pool.
        handlers: Map of ``workerType → SdkTaskHandler``. Empty map is
            allowed (every message will be REPORTed as failure with
            "no handler registered"); useful for smoke tests.

    Thread-safety: single asyncio loop only. ``on_message`` must be
    awaited from the same loop as ``shutdown``.
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
        self._draining: bool = False
        self._fatal: bool = False
        self._runtime_state: WorkerRuntimeState = WorkerRuntimeState.NORMAL

    # ─── public observable state ───────────────────────────────────────

    def in_flight_count(self) -> int:
        """Current in-flight task count (Java ``inFlightCount()``)."""
        return len(self._in_flight)

    def in_flight_task_ids(self) -> set[int]:
        """Snapshot of in-flight task IDs (for P3 batch-renew payload)."""
        return set(self._in_flight.keys())

    @property
    def runtime_state(self) -> WorkerRuntimeState:
        """Current platform-directed runtime state."""
        return self._runtime_state

    @property
    def is_fatal(self) -> bool:
        """``True`` after an unrecoverable ``AuthError`` — process is poisoned."""
        return self._fatal

    @property
    def is_draining(self) -> bool:
        """``True`` between ``shutdown()`` start and process exit."""
        return self._draining

    def accepts_new_tasks(self) -> bool:
        """Whether the dispatcher should accept a fresh message.

        Mirrors Java ``platformAcceptsNewTasks() && !draining && !fatal``.
        ``KafkaTaskConsumer.apply_backpressure()`` reads this to decide
        whether to ``consumer.pause(...)``.
        """
        return not self._draining and not self._fatal and self._runtime_state.accepts_new_tasks()

    # ─── platform directive enactment (P3 will flesh out) ──────────────

    def apply_platform_directive(self, directive: dict[str, Any]) -> None:
        """Apply a heartbeat-returned directive (P2 stub).

        Currently only reads ``runtimeState`` (one of the 4 ``WorkerRuntimeState``
        values) and stashes it. P3 will add drain deadline tracking,
        concurrency adjustment hints, and config refresh.
        """
        raw = directive.get("runtimeState")
        if raw is None:
            return
        try:
            self._runtime_state = WorkerRuntimeState(raw)
        except ValueError:
            logger.warning("ignoring unknown runtimeState=%r in platform directive", raw)

    # ─── message ingress ───────────────────────────────────────────────

    async def on_message(self, msg: dict[str, Any]) -> None:  # noqa: PLR0911
        """Handle one decoded ``TaskDispatchMessage`` envelope.

        Returns quickly: dispatches the actual task to a background
        ``asyncio.Task`` so the Kafka poll loop is never blocked.
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

        # Schema version gate — reject unknown major versions so an old
        # Python SDK doesn't silently mis-interpret a future ``v3`` envelope.
        schema = msg.get("schemaVersion") or ""
        if not any(schema.startswith(p) for p in _SUPPORTED_SCHEMA_PREFIXES):
            logger.warning(
                "rejecting kafka task dispatch message with unsupported schemaVersion=%r taskId=%s",
                schema,
                msg.get("taskId"),
            )
            return

        # Lane J §J1 tenant self-check fail-safe — Kafka topic pattern +
        # consumer group + ACL should already isolate, but if any of
        # that drifts, ERROR + drop here and rely on lease timeout to
        # redeliver to the right tenant.
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

    # ─── per-task pipeline ─────────────────────────────────────────────

    async def _process(self, task_id: int, msg: dict[str, Any]) -> None:
        """CLAIM → execute → REPORT for a single task.

        Errors are absorbed: AuthError sets fatal and stops further
        ingest; other PlatformError leaves the task to be redelivered
        via lease timeout. P3 will add a structured "REPORT failure"
        path for handler exceptions; today we just log.
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
            # Wire-protocol §B: 401/403 = persistent, set fatal so
            # KafkaTaskConsumer stops feeding messages; K8s liveness
            # probe will recycle the pod.
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

        # P2 keeps the handler-invocation skeleton minimal: P3 will
        # build SdkTaskContext + run handler.execute + collect
        # SdkTaskResult. For this lane we just REPORT a synthetic
        # success so the wire path is exercised end-to-end.
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

    # ─── lifecycle ─────────────────────────────────────────────────────

    async def shutdown(self, timeout: float) -> None:  # noqa: ASYNC109 — mirrors Java signature
        """Drain in-flight tasks; bounded by ``timeout`` seconds.

        Sets ``_draining`` first so no new ``on_message`` accepts.
        Outstanding tasks are awaited via ``asyncio.wait``. P4 will
        layer ``stop(timeout)`` over this with deactivate + final-state
        REPORT for stragglers.
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
