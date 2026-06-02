"""Dispatch shape — tenant -> external push (ADR-036).

Python equivalent of Java ``SdkAbstractDispatchHandler``: fan-out push
to many external targets (HTTP / SFTP / queue). Per-target failures are
counted as ``failed`` and **do not** abort the batch — same semantics as
the Java ``SdkAbstractTypedDispatchHandler`` template loop.

Template order::

    _resolve_targets (async iterator of Target) ->
    _dispatch_to_target (per target; may raise -> failed++)
"""

from __future__ import annotations

import logging
from abc import abstractmethod
from collections.abc import AsyncIterator
from typing import Any, final

from batch_worker_sdk.handler._base import (
    HANDLER_ERROR_CODE,
    SdkAbstractTaskHandler,
    SdkRowResult,
)
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult

logger = logging.getLogger(__name__)

DispatchResult = Any  # opaque per-target response


class SdkAbstractDispatchHandler[T](SdkAbstractTaskHandler):
    """Fan-out push to many external targets.

    Mirror of Java ``SdkAbstractDispatchHandler<R>``. The Python form
    collapses Java's 4-hook split (``selectPayload`` / ``buildRequest``
    / ``push`` / ``onResponse``) into a 2-hook contract
    (``_resolve_targets`` / ``_dispatch_to_target``) — tenants who need
    finer granularity layer it inside their own
    ``_dispatch_to_target``. The fan-out semantics (per-target catch,
    increment-failed, continue) match Java 1:1.
    """

    @abstractmethod
    def _resolve_targets(self, ctx: SdkTaskContext) -> AsyncIterator[T]:
        """Return an async iterator over targets / payload items."""

    @abstractmethod
    async def _dispatch_to_target(
        self,
        ctx: SdkTaskContext,
        target: T,
    ) -> DispatchResult:
        """Push to a single target. Raise to count this target as failed."""

    @final
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        counts = SdkRowResult()
        try:
            async for target in self._resolve_targets(ctx):
                try:
                    await self._dispatch_to_target(ctx, target)
                    counts.inc_success()
                except Exception as item_ex:
                    counts.inc_failed()
                    logger.warning("dispatch item failed: %s", item_ex)
            return SdkTaskResult.success_with(
                output=counts.to_output(),
                message=f"dispatched {counts.success()}/{counts.total()}",
            )
        except Exception as ex:
            return SdkTaskResult.fail(
                HANDLER_ERROR_CODE,
                str(ex) or type(ex).__name__,
                cause=ex,
            )


__all__ = ["DispatchResult", "SdkAbstractDispatchHandler"]
