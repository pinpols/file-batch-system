"""High-level SDK entrypoint mirroring Java ``BatchPlatformClient``.

Single user-facing class that wires the pieces together:

1. :class:`PlatformHttpClient` (Lane Q)
2. :class:`TaskDispatcher` (Lane S P2 — imported lazily so this lane
   compiles + tests against an injected fake until Lane S merges).
3. :class:`KafkaTaskConsumer` (Lane S P2 — same lazy-import treatment).
4. :class:`HeartbeatScheduler` + :class:`LeaseRenewalScheduler` (this
   lane — P3).

Lifecycle:

- :meth:`register_handler` (pre-start, mirrors Java
  ``Builder.register``).
- :meth:`start` runs ``validate_timings`` → register HTTP →
  schedulers → kafka consumer. **Scheduler-before-Kafka** is critical:
  if a task arrives before the first heartbeat the orch may not know
  about us yet (Java equivalent: same order, see
  ``BatchPlatformClient.start`` in the Java SDK).
- :meth:`stop` — the **real** stop implementation lands in Lane U
  (``_lifecycle.stop_with_timeout``). We probe for it; when absent the
  ``_stop_minimal_phase3`` fallback kicks in so the Phase 3 PR's tests
  still pass.

Tests inject ``dispatcher`` / ``kafka_consumer`` to stay decoupled from
the Lane S / Lane U merge order.
"""

from __future__ import annotations

import asyncio
import logging
from datetime import timedelta
from typing import Any, Protocol

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.scheduler._heartbeat import DispatcherLike, HeartbeatScheduler
from batch_worker_sdk.scheduler._lease import LeaseRenewalScheduler

logger = logging.getLogger(__name__)


class _KafkaConsumerLike(Protocol):
    """Subset of ``KafkaTaskConsumer`` the client touches."""

    async def start(self) -> None: ...
    async def stop(self) -> None: ...


# Factory aliases — keep types in one place + ease the Lane S merge:
DispatcherFactory = Any  # callable: (config, http, handlers) -> DispatcherLike
KafkaFactory = Any  # callable: (config, dispatcher) -> _KafkaConsumerLike


class BatchPlatformClient:
    """SDK entrypoint mirroring Java ``com.example.batch.sdk.client.BatchPlatformClient``.

    Typical usage::

        client = BatchPlatformClient(config)
        client.register_handler(MyImportHandler())
        await client.start()
        try:
            await stop_signal.wait()  # SIGTERM hook
        finally:
            await client.stop(timeout=30)

    Args:
        config: Pre-validated :class:`BatchPlatformClientConfig`. Timing
            sanity is re-checked on :meth:`start` so a swapped config
            also fails fast.
        http: Inject a preconstructed :class:`PlatformHttpClient` (tests).
            Production callers leave ``None``.
        dispatcher_factory / kafka_factory: Injection points for the
            Lane S ``TaskDispatcher`` / ``KafkaTaskConsumer``. When
            ``None`` the constructors are imported lazily from
            ``batch_worker_sdk.dispatcher`` / ``batch_worker_sdk._kafka``
            inside :meth:`start` — that import only happens once Lane S
            has merged, so this module never blocks on it at import time.
    """

    def __init__(
        self,
        config: BatchPlatformClientConfig,
        *,
        http: PlatformHttpClient | None = None,
        dispatcher_factory: DispatcherFactory | None = None,
        kafka_factory: KafkaFactory | None = None,
    ) -> None:
        self._config = config
        self._http: PlatformHttpClient = http if http is not None else PlatformHttpClient(config)
        self._owns_http: bool = http is None
        self._handlers: dict[str, SdkTaskHandler] = {}
        self._dispatcher_factory = dispatcher_factory
        self._kafka_factory = kafka_factory

        self._dispatcher: DispatcherLike | None = None
        self._kafka: _KafkaConsumerLike | None = None
        self._heartbeat: HeartbeatScheduler | None = None
        self._lease: LeaseRenewalScheduler | None = None

        self._started: bool = False
        self._start_lock: asyncio.Lock = asyncio.Lock()

    # ─── pre-start configuration ───────────────────────────────────────

    def register_handler(self, handler: SdkTaskHandler) -> None:
        """Register an :class:`SdkTaskHandler` (Java ``Builder.register``).

        Must be called before :meth:`start`. Raises on duplicate
        ``task_type`` so a misconfigured worker fails noisily instead
        of silently dropping dispatches to the loser.
        """
        if self._started:
            raise RuntimeError("cannot register handlers after start()")
        task_type = handler.task_type()
        if not task_type or not task_type.strip():
            raise ValueError("handler.task_type() must be non-blank")
        if task_type in self._handlers:
            raise ValueError(f"duplicate handler for task_type={task_type!r}")
        self._handlers[task_type] = handler

    # ─── observable state ──────────────────────────────────────────────

    @property
    def started(self) -> bool:
        return self._started

    @property
    def dispatcher(self) -> DispatcherLike | None:
        """Underlying dispatcher (post-start). Exposed for tests + ops."""
        return self._dispatcher

    @property
    def http(self) -> PlatformHttpClient:
        """Underlying HTTP client. Exposed for tests + advanced users."""
        return self._http

    # ─── lifecycle ─────────────────────────────────────────────────────

    async def start(self) -> None:
        """Full start sequence.

        Order:

        1. ``config._validate_timings()`` — re-run in case a caller built
           the config bypassing pydantic (mostly belt-and-braces).
        2. ``http.register`` — fail-fast if orch / api-key / network is broken.
        3. Build dispatcher + schedulers.
        4. Start heartbeat + lease schedulers (so the first kafka message
           never lands before orch knows we're alive).
        5. Start Kafka consumer.

        Raises:
            RuntimeError: double-start or no handlers registered.
            ValueError: timings invalid.
            PlatformError: register failed.
        """
        async with self._start_lock:
            if self._started:
                raise RuntimeError("BatchPlatformClient already started")
            if not self._handlers:
                raise RuntimeError("at least one SdkTaskHandler must be registered")

            self._config._validate_timings()

            await self._http.register(self._build_register_body())

            self._dispatcher = self._build_dispatcher()
            self._heartbeat = HeartbeatScheduler(self._config, self._http, self._dispatcher)
            self._lease = LeaseRenewalScheduler(self._config, self._http, self._dispatcher)

            # Scheduler first, kafka second — see class docstring.
            await self._heartbeat.start()
            await self._lease.start()

            self._kafka = self._build_kafka(self._dispatcher)
            if self._kafka is not None:
                await self._kafka.start()

            self._started = True
            logger.info(
                "BatchPlatformClient started: tenant=%s worker=%s handlers=%s",
                self._config.tenant_id,
                self._config.worker_code,
                sorted(self._handlers.keys()),
            )

    async def stop(self, timeout: timedelta | float = 30.0) -> None:  # noqa: ASYNC109 — total-budget semantic, not per-await
        """Graceful stop.

        The real budgeted-stop implementation lives in Lane U
        (``_lifecycle.stop_with_timeout``). We try to import it; if it's
        not yet merged we fall through to :meth:`_stop_minimal_phase3`
        which still honours the timeout but doesn't yet split the budget
        across kafka-join / drain / scheduler / deactivate phases.
        """
        if not self._started:
            return
        timeout_s = timeout.total_seconds() if isinstance(timeout, timedelta) else float(timeout)
        import importlib  # noqa: PLC0415 — Lane U merge probe

        try:
            lifecycle = importlib.import_module("batch_worker_sdk.internal._lifecycle")
        except ImportError:
            await self._stop_minimal_phase3(timeout_s)
        else:
            await lifecycle.stop_with_timeout(self, timeout_s)
        self._started = False

    async def _stop_minimal_phase3(self, timeout_s: float) -> None:
        """Phase 3 fallback shutdown.

        TODO(Lane U): replace with ``_lifecycle.stop_with_timeout`` once
        Lane U merges. Order matches Java ``BatchPlatformClient.stop``:

        1. Stop Kafka consumer (stop accepting dispatches).
        2. ``dispatcher.shutdown(remaining)`` — drain in-flight tasks.
        3. Stop heartbeat + lease schedulers.
        4. ``deactivate`` — best-effort goodbye to orch.
        """
        logger.info("BatchPlatformClient stopping (phase-3-fallback, timeout=%.1fs)", timeout_s)
        loop = asyncio.get_running_loop()
        deadline = loop.time() + max(0.0, timeout_s)

        if self._kafka is not None:
            await self._kafka.stop()

        if self._dispatcher is not None:
            remaining = max(0.0, deadline - loop.time())
            shutdown = getattr(self._dispatcher, "shutdown", None)
            if shutdown is not None:
                try:
                    await shutdown(remaining)
                except Exception as ex:
                    logger.warning("dispatcher.shutdown raised: %s", ex)

        if self._heartbeat is not None:
            await self._heartbeat.stop()
        if self._lease is not None:
            await self._lease.stop()

        try:
            await self._http.deactivate(
                self._config.worker_code,
                {
                    "tenantId": self._config.tenant_id,
                    "workerCode": self._config.worker_code,
                    "status": "OFFLINE",
                },
            )
        except Exception as ex:
            logger.warning("deactivate call failed (ignored): %s", ex)

        if self._owns_http:
            await self._http.close()

    # ─── internals ─────────────────────────────────────────────────────

    def _build_register_body(self) -> dict[str, Any]:
        """Build the worker-register body matching Java ``WorkerHeartbeatDto`` shape."""
        from batch_worker_sdk.scheduler._heartbeat import _utc_now_iso  # noqa: PLC0415

        body: dict[str, Any] = {
            "tenantId": self._config.tenant_id,
            "workerCode": self._config.worker_code,
            "workerGroup": "sdk-self-hosted",
            "status": "RUNNING",
            "heartbeatAt": _utc_now_iso(),
            "currentLoad": 0,
            "capabilityTags": sorted(self._handlers.keys()),
            "sdkVersion": self._config.sdk_version,
        }
        if self._config.build_id:
            body["buildId"] = self._config.build_id
        descriptors: list[dict[str, Any]] = []
        for handler in self._handlers.values():
            desc = handler.descriptor()
            if desc is not None:
                # Pydantic model → dict; pydantic v2 prefers model_dump
                if hasattr(desc, "model_dump"):
                    descriptors.append(desc.model_dump(exclude_none=True))
                else:
                    descriptors.append(dict(desc))
        if descriptors:
            body["taskTypes"] = descriptors
        return body

    def _build_dispatcher(self) -> DispatcherLike:
        """Build the dispatcher; defer to the Lane S import once it's merged.

        Tests inject ``dispatcher_factory`` to avoid both the import probe
        and the Lane S concrete class.
        """
        if self._dispatcher_factory is not None:
            built: DispatcherLike = self._dispatcher_factory(
                self._config, self._http, dict(self._handlers)
            )
            return built
        # Lazy import — Lane S supplies ``dispatcher.TaskDispatcher``. We
        # use ``importlib`` so mypy strict + ruff don't complain about a
        # missing module while Lane S is still in-flight.
        import importlib  # noqa: PLC0415 — Lane S merge probe

        try:
            mod = importlib.import_module("batch_worker_sdk.dispatcher.dispatcher")
        except ImportError as ex:
            raise RuntimeError(
                "TaskDispatcher not yet available — Lane S (P2) has not landed; "
                "inject a dispatcher_factory in tests until then"
            ) from ex
        cls = mod.TaskDispatcher
        built = cls(self._config, self._http, dict(self._handlers))
        return built

    def _build_kafka(self, dispatcher: DispatcherLike) -> _KafkaConsumerLike | None:
        """Build the Kafka consumer if a factory was supplied.

        We deliberately do **not** auto-import ``batch_worker_sdk._kafka``
        here — kafka config knobs live in Lane S's config additions and
        we don't want this lane to depend on those fields. Production
        callers reach the kafka path by passing ``kafka_factory=...`` or
        by Lane S replacing this method.
        """
        if self._kafka_factory is None:
            logger.info(
                "no kafka_factory supplied; running scheduler-only "
                "(HTTP heartbeat + lease renew still active)"
            )
            return None
        built: _KafkaConsumerLike = self._kafka_factory(self._config, dispatcher)
        return built
