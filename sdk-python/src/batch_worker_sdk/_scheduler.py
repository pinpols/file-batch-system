"""Async heartbeat + lease-renewal schedulers.

Python ports of Java ``com.example.batch.sdk.scheduler.HeartbeatScheduler``
and ``LeaseRenewalScheduler``. Both run as long-lived ``asyncio.Task``s
launched by :class:`~batch_worker_sdk.client.BatchPlatformClient.start`.

Design parity with Java (key invariants):

- **HTTP failures never kill the loop.** Java wraps the entire tick in
  ``try { ... } catch (Throwable) { log.warn }``. We do the same: any
  exception is caught and logged at WARN; the orchestrator's
  missed-heartbeat threshold handles the worker-side gap.
- **HeartbeatScheduler honours ``nextHeartbeatHint``** (Java Lane I,
  PR #251). The orch can push the next-tick interval up or down; we
  clamp it to ``[1s, baseline * 10]`` so a misconfigured orch can't
  storm us at 100ms or starve heartbeats at 1h.
- **LeaseRenewalScheduler renews every in-flight task** every tick.
  Per-task ``cancelRequested=True`` flips dispatcher-side cancellation
  (Lane U handles the actual handler signal); 404 / 410 = lease revoked,
  treated identically (orchestrator already re-dispatched, our copy must
  stop).

Decoupling note: schedulers depend on a tiny ``DispatcherLike`` protocol
rather than the concrete :class:`TaskDispatcher` (Lane S P2). This keeps
mypy strict happy in the worktree window where Lane S has not yet
landed, and lets test code substitute a tiny fake without bringing in
``aiokafka`` etc.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from datetime import UTC, datetime
from typing import Any, Protocol, runtime_checkable

from batch_worker_sdk._directive import ParsedDirective, parse_directive
from batch_worker_sdk._http import PlatformHttpClient
from batch_worker_sdk.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import PlatformError

logger = logging.getLogger(__name__)


# ─── Java HeartbeatScheduler constants ─────────────────────────────────
# Mirror MIN_HINT_MS=1000 and MAX_HINT_MULTIPLIER=10.
_MIN_HINT_S: float = 1.0
_MAX_HINT_MULTIPLIER: int = 10


@runtime_checkable
class DispatcherLike(Protocol):
    """Subset of ``TaskDispatcher`` the schedulers need.

    Lane S (P2) ``TaskDispatcher`` implements this naturally; the
    explicit ``Protocol`` here keeps the schedulers loosely coupled and
    independently testable.
    """

    def in_flight_count(self) -> int: ...
    def in_flight_task_ids(self) -> set[int]: ...
    def apply_platform_directive(self, directive: Any) -> None: ...
    def mark_cancel_requested(self, task_id: int, reason: str) -> None: ...


def _utc_now_iso() -> str:
    """RFC 3339 timestamp matching Java ``Instant.now().toString()``."""
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


class HeartbeatScheduler:
    """Periodic heartbeat poster with orch-driven re-pacing.

    Java equivalent: ``HeartbeatScheduler`` (fixed-delay schedule, hint
    re-pace via ``applyHeartbeatHint``).

    Args:
        config: Validated SDK config (provides ``heartbeat_interval``).
        http: Live :class:`PlatformHttpClient`.
        dispatcher: Anything matching :class:`DispatcherLike` — the
            scheduler reads ``in_flight_count`` for the heartbeat body
            and forwards parsed directives to ``apply_platform_directive``.
    """

    def __init__(
        self,
        config: BatchPlatformClientConfig,
        http: PlatformHttpClient,
        dispatcher: DispatcherLike,
    ) -> None:
        self._config = config
        self._http = http
        self._dispatcher = dispatcher
        self._baseline_s: float = config.heartbeat_interval.total_seconds()
        self._next_interval_s: float = self._baseline_s
        self._task: asyncio.Task[None] | None = None
        self._stop_event: asyncio.Event = asyncio.Event()

    # ─── observable state (tests + diagnostics) ────────────────────────

    @property
    def current_interval_s(self) -> float:
        """Current effective interval in seconds (post-hint clamp)."""
        return self._next_interval_s

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    # ─── lifecycle ─────────────────────────────────────────────────────

    async def start(self) -> None:
        """Launch the heartbeat loop as a background task.

        Idempotent: a second ``start()`` is a no-op (logged at DEBUG).
        """
        if self.running:
            logger.debug("HeartbeatScheduler already running, ignoring start()")
            return
        self._stop_event.clear()
        self._task = asyncio.create_task(self._run_loop(), name="sdk-heartbeat")
        logger.info("HeartbeatScheduler started: interval=%.3fs", self._baseline_s)

    async def stop(self) -> None:
        """Signal stop + await loop exit. Safe to call multiple times."""
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

    # ─── single tick (exposed for unit tests) ──────────────────────────

    async def tick(self) -> ParsedDirective | None:
        """Run exactly one heartbeat round + apply the response directive.

        Returns the parsed directive (for tests / observability), or
        ``None`` if the heartbeat HTTP call failed.

        Failures are absorbed: caller is the loop, which must not die.
        """
        body: dict[str, Any] = {
            "tenantId": self._config.tenant_id,
            "workerCode": self._config.worker_code,
            "status": "RUNNING",
            "heartbeatAt": _utc_now_iso(),
            "currentLoad": self._dispatcher.in_flight_count(),
        }
        try:
            resp = await self._http.heartbeat(self._config.worker_code, body)
        except PlatformError as ex:
            # Wire-protocol §B: orch missed-heartbeat threshold tolerates a
            # few of these; we just log and let the loop sleep & retry.
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

        # Lane I (ADR-035 §11): hint-driven re-pacing.
        if directive.next_heartbeat_hint is not None:
            self._apply_hint(directive.next_heartbeat_hint.total_seconds())
        return directive

    # ─── internals ─────────────────────────────────────────────────────

    async def _run_loop(self) -> None:
        """Fixed-delay scheduler — each iteration sleeps the *current* interval.

        Mirrors Java's ``scheduleWithFixedDelay``: the next tick is paced
        relative to **completion** of the previous, not start, so a slow
        heartbeat can never queue up and stampede the platform.
        """
        try:
            while not self._stop_event.is_set():
                try:
                    await asyncio.wait_for(
                        self._stop_event.wait(),
                        timeout=self._next_interval_s,
                    )
                    # Event set → exit loop
                    return
                except TimeoutError:
                    pass
                await self.tick()
        except asyncio.CancelledError:
            raise

    def _apply_hint(self, hint_s: float) -> None:
        """Clamp + apply a heartbeat-interval hint from orch.

        - ``< 1s`` → 1s (anti-flood)
        - ``> 10 * baseline`` → 10 * baseline (anti-starve)
        - equal to current → no-op
        """
        max_s = self._baseline_s * _MAX_HINT_MULTIPLIER
        clamped = max(_MIN_HINT_S, min(max_s, hint_s))
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


class LeaseRenewalScheduler:
    """Periodic lease-renewal for every in-flight task.

    Java equivalent: ``LeaseRenewalScheduler``. Per-task semantics:

    - ``cancelRequested=True`` in response → flip dispatcher-side cancel
      signal (``mark_cancel_requested``).
    - 404 / 410 → lease already revoked; treat same as cancel.
    - Other failures → WARN, retry next tick.

    Args:
        config: Validated SDK config (provides ``lease_renew_interval``).
        http: Live :class:`PlatformHttpClient`.
        dispatcher: :class:`DispatcherLike`.
    """

    def __init__(
        self,
        config: BatchPlatformClientConfig,
        http: PlatformHttpClient,
        dispatcher: DispatcherLike,
    ) -> None:
        self._config = config
        self._http = http
        self._dispatcher = dispatcher
        self._interval_s: float = config.lease_renew_interval.total_seconds()
        self._task: asyncio.Task[None] | None = None
        self._stop_event: asyncio.Event = asyncio.Event()

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    async def start(self) -> None:
        """Launch the lease-renewal loop. Idempotent."""
        if self.running:
            logger.debug("LeaseRenewalScheduler already running, ignoring start()")
            return
        self._stop_event.clear()
        self._task = asyncio.create_task(self._run_loop(), name="sdk-lease-renewal")
        logger.info("LeaseRenewalScheduler started: interval=%.3fs", self._interval_s)

    async def stop(self) -> None:
        """Signal stop + await loop exit. Safe to call multiple times."""
        if self._task is None:
            return
        self._stop_event.set()
        try:
            await asyncio.wait_for(self._task, timeout=self._interval_s + 1.0)
        except TimeoutError:
            logger.warning("LeaseRenewalScheduler did not exit cleanly; cancelling")
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._task
        self._task = None
        logger.info("LeaseRenewalScheduler stopped")

    async def tick(self) -> None:
        """Renew every in-flight lease once. Per-task failures isolated."""
        ids = self._dispatcher.in_flight_task_ids()
        if not ids:
            return
        # Iterate over a sorted copy so test assertions are deterministic
        # and we never mutate while iterating (dispatcher pops as tasks
        # complete in the background).
        for task_id in sorted(ids):
            await self._renew_one(task_id)

    async def _renew_one(self, task_id: int) -> None:
        body: dict[str, Any] = {
            "tenantId": self._config.tenant_id,
            "workerId": self._config.worker_code,
        }
        try:
            resp = await self._http.renew(task_id, body)
        except PlatformError as ex:
            # ``_http.renew`` surfaces 404/410 as ConflictError/Persistent;
            # treat statusCode if available, otherwise WARN-only.
            status = getattr(ex, "status_code", None)
            if status in (404, 410):
                logger.warning(
                    "lease revoked for taskId=%s (HTTP %s) — signalling stop",
                    task_id,
                    status,
                )
                self._safe_mark_cancel(task_id, "lease-revoked")
            else:
                logger.warning("renew failed for taskId=%s: %s", task_id, ex)
            return
        except Exception as ex:
            logger.warning("renew unexpected error for taskId=%s: %s", task_id, ex)
            return

        if resp and resp.get("cancelRequested") is True:
            logger.info("platform requested cancel for taskId=%s", task_id)
            self._safe_mark_cancel(task_id, "platform-cancel")

    def _safe_mark_cancel(self, task_id: int, reason: str) -> None:
        """Dispatcher may not have ``mark_cancel_requested`` yet (Lane U).

        We use ``getattr`` so this lane is forward-compatible with the
        Lane U / Lane S extension. If the method is missing we WARN once
        per call so it stays visible during the staggered merge window.
        """
        mark = getattr(self._dispatcher, "mark_cancel_requested", None)
        if mark is None:
            logger.warning(
                "dispatcher missing mark_cancel_requested(taskId=%s, reason=%s) — "
                "Lane S/U extension not yet wired",
                task_id,
                reason,
            )
            return
        try:
            mark(task_id, reason)
        except Exception as ex:
            logger.warning("mark_cancel_requested raised for taskId=%s: %s", task_id, ex)

    async def _run_loop(self) -> None:
        try:
            while not self._stop_event.is_set():
                try:
                    await asyncio.wait_for(
                        self._stop_event.wait(),
                        timeout=self._interval_s,
                    )
                    return
                except TimeoutError:
                    pass
                try:
                    await self.tick()
                except Exception as ex:
                    logger.warning("lease renewal tick failed: %s", ex)
        except asyncio.CancelledError:
            raise
