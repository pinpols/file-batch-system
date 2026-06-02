"""Export shape — tenant -> external template (ADR-036).

Python equivalent of Java ``SdkAbstractExportHandler``: tenant DB -> file
(or other sink). Template order::

    _open_destination -> _query_rows (async iterator) ->
    _write_row (per row) -> (finally) _close_destination
"""

from __future__ import annotations

import contextlib
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


class SdkAbstractExportHandler[R](SdkAbstractTaskHandler):
    """Tenant-DB -> file/external export shape.

    Mirror of Java ``SdkAbstractExportHandler<R>``. Java's ``Stream<R>
    streamRows`` becomes an :class:`typing.AsyncIterator`; row-formatting
    and the sink open/close pair are async methods.
    """

    async def _open_destination(self, ctx: SdkTaskContext) -> None:
        """Open the output sink (create file / S3 multipart / writer). Default no-op."""
        return None

    @abstractmethod
    def _query_rows(self, ctx: SdkTaskContext) -> AsyncIterator[R]:
        """Return an async iterator of source rows."""

    @abstractmethod
    async def _write_row(self, ctx: SdkTaskContext, row: R) -> None:
        """Format and write a single row to the sink."""

    async def _close_destination(self, ctx: SdkTaskContext) -> None:
        """Flush / close / upload. Default no-op."""
        return None

    @final
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        counts = SdkRowResult()
        opened = False
        try:
            await self._open_destination(ctx)
            opened = True
            async for row in self._query_rows(ctx):
                await self._write_row(ctx, row)
                counts.inc_success()
            return SdkTaskResult.success_with(
                output=counts.to_output(),
                message=f"exported {counts.success()} rows",
            )
        except Exception as ex:
            return SdkTaskResult.fail(
                HANDLER_ERROR_CODE,
                str(ex) or type(ex).__name__,
                cause=ex,
            )
        finally:
            if opened:
                # Cleanup failures are silenced so the main result isn't replaced.
                with contextlib.suppress(Exception):
                    await self._close_destination(ctx)


__all__ = ["SdkAbstractExportHandler"]
