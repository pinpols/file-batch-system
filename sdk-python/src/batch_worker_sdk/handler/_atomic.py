"""Atomic shape — single-call handler template (ADR-036).

Python equivalent of Java ``SdkAbstractAtomicHandler``: single atomic
invocation (shell / single SQL / HTTP / pure compute). Subclasses only
implement :meth:`_do_invoke`; exceptions are caught by the parent
template and converted to :meth:`SdkTaskResult.fail`, so tenant code
doesn't assemble a result object by hand.
"""

from __future__ import annotations

from abc import abstractmethod
from typing import Any, final

from batch_worker_sdk.handler._base import (
    HANDLER_ERROR_CODE,
    SdkAbstractTaskHandler,
)
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult


class SdkAbstractAtomicHandler[R](SdkAbstractTaskHandler):
    """Single-shot atomic-call handler.

    Mirror of Java ``SdkAbstractAtomicHandler<R>``. The template wraps
    :meth:`_do_invoke` once and packs the return value through
    :meth:`as_output` into :meth:`SdkTaskResult.success_with`. Any
    exception is converted to a failure result with code
    ``HANDLER_ERROR``.
    """

    @abstractmethod
    async def _do_invoke(self, ctx: SdkTaskContext) -> R:
        """Tenant-implemented single atomic call."""

    def as_output(self, result: R | None) -> dict[str, Any]:
        """Map the invocation return value into the ``output`` dict.

        Default: ``{"result": result}`` when non-None, else ``{}`` —
        identical to Java ``asOutput``.
        """
        if result is None:
            return {}
        return {"result": result}

    @final
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        try:
            return SdkTaskResult.success_with(
                output=self.as_output(await self._do_invoke(ctx)),
                message="invoked",
            )
        except Exception as ex:  # noqa: BLE001 — shape-level catch by design
            return SdkTaskResult.fail(
                HANDLER_ERROR_CODE,
                str(ex) or type(ex).__name__,
                cause=ex,
            )


__all__ = ["SdkAbstractAtomicHandler"]
