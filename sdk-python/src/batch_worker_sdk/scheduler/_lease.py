"""Async lease-renewal scheduler.

Python port of Java ``com.example.batch.sdk.scheduler.LeaseRenewalScheduler``.
Runs as a long-lived ``asyncio.Task`` launched by
:class:`~batch_worker_sdk.client.client.BatchPlatformClient.start`.

Per-task semantics:

- ``cancelRequested=True`` in response → flip dispatcher-side cancel
  signal (``mark_cancel_requested``).
- 404 / 410 → lease already revoked; treat same as cancel.
- Other failures → WARN, retry next tick.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from typing import Any

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import PlatformError
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.scheduler._heartbeat import DispatcherLike

logger = logging.getLogger(__name__)


class LeaseRenewalScheduler:
    """Periodic lease-renewal for every in-flight task.

    Java equivalent: ``LeaseRenewalScheduler``.

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
