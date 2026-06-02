"""Process shape — tenant -> tenant transform pipeline (ADR-036).

Python equivalent of Java ``SdkAbstractProcessHandler<I, O>``. Template
order::

    _open_input (async iterator of InputRow) -> _transform (per row) ->
    _write_output (per OutputRow)

``_transform`` returning ``None`` marks the row as ``skipped`` (matches
Java semantics).
"""

from __future__ import annotations

from abc import abstractmethod
from collections.abc import AsyncIterator
from typing import final

from batch_worker_sdk.handler._base import (
    HANDLER_ERROR_CODE,
    SdkAbstractTaskHandler,
    SdkRowResult,
)
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult


class SdkAbstractProcessHandler[InputRow, OutputRow](SdkAbstractTaskHandler):
    """Tenant-DB -> tenant-DB transform pipeline.

    Mirror of Java ``SdkAbstractProcessHandler<I, O>``. The Python
    base flattens the per-row vs batch-write split into a single
    ``_write_output(ctx, output)`` hook — Java's typed base buffers and
    bulk-upserts; tenants who need that here can buffer inside their
    own ``_write_output``. (The shape contract — null transform =
    skip; per-row success/skipped counters — stays 1:1 with Java.)
    """

    @abstractmethod
    def _open_input(self, ctx: SdkTaskContext) -> AsyncIterator[InputRow]:
        """Return an async iterator over input rows."""

    @abstractmethod
    async def _transform(
        self, ctx: SdkTaskContext, input_row: InputRow
    ) -> OutputRow | None:
        """Convert one input row to an output row.

        Returning ``None`` marks the row as ``skipped`` (no write).
        """

    @abstractmethod
    async def _write_output(self, ctx: SdkTaskContext, output_row: OutputRow) -> None:
        """Persist one output row."""

    @final
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        counts = SdkRowResult()
        try:
            async for row in self._open_input(ctx):
                out = await self._transform(ctx, row)
                if out is None:
                    counts.inc_skipped()
                else:
                    await self._write_output(ctx, out)
                    counts.inc_success()
            return SdkTaskResult.success_with(
                output=counts.to_output(),
                message=f"processed {counts.success()} rows",
            )
        except Exception as ex:
            return SdkTaskResult.fail(
                HANDLER_ERROR_CODE,
                str(ex) or type(ex).__name__,
                cause=ex,
            )


__all__ = ["SdkAbstractProcessHandler"]
