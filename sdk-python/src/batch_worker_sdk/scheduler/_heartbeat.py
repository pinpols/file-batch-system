"""Async heartbeat scheduler.

Python port of Java ``com.example.batch.sdk.scheduler.HeartbeatScheduler``.
Runs as a long-lived ``asyncio.Task`` launched by
:class:`~batch_worker_sdk.client.client.BatchPlatformClient.start`.

Design parity with Java (key invariants):

- **HTTP failures never kill the loop.** Java wraps the entire tick in
  ``try { ... } catch (Throwable) { log.warn }``. We do the same: any
  exception is caught and logged at WARN; the orchestrator's
  missed-heartbeat threshold handles the worker-side gap.
- **HeartbeatScheduler honours ``nextHeartbeatHint``** (Java Lane I,
  PR #251). The orch can push the next-tick interval up or down; we
  clamp it to ``[1s, baseline * 10]`` so a misconfigured orch can't
  storm us at 100ms or starve heartbeats at 1h.

Decoupling note: schedulers depend on a tiny :class:`DispatcherLike`
protocol rather than the concrete ``TaskDispatcher`` (Lane S P2). This
keeps mypy strict happy in the worktree window where Lane S has not yet
landed, and lets test code substitute a tiny fake without bringing in
``aiokafka`` etc.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from datetime import UTC, datetime
from typing import Any, Protocol, runtime_checkable

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import PlatformError
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.scheduler._directive import ParsedDirective, parse_directive

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
