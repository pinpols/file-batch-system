"""Import shape — external -> tenant template (ADR-036).

Python equivalent of Java ``SdkAbstractImportHandler``: file/stream ->
tenant DB. Template order::

    _open_source -> _read_rows (async iterator) -> batch buffer ->
    _load_batch -> (finally) _close_source

Subclasses fill 3 required hooks (``_read_rows`` / ``_load_batch``); the
others have safe defaults. Per-shape ``_do_execute`` is :func:`final`.
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


class SdkAbstractImportHandler[R](SdkAbstractTaskHandler):
    """File/stream -> tenant-DB import shape.

    Mirror of Java ``SdkAbstractImportHandler<R>``. Java exposes a
    ``Stream<R>`` for ``readRows``; the Python form uses an
    :class:`typing.AsyncIterator` (async generator) because the SDK's
    I/O surface is async-only.
    """

    DEFAULT_BATCH_SIZE: int = 1000

    async def _open_source(self, ctx: SdkTaskContext) -> None:
        """Open the data source (connect SFTP / download file / open stream). Default no-op."""
        return None

    @abstractmethod
    def _read_rows(self, ctx: SdkTaskContext) -> AsyncIterator[R]:
        """Return an async iterator of parsed rows (one per record).

        Aligns with Java ``Stream<R> readRows(...)``. Implementations
        typically write this as an ``async def`` generator.
        """

    @abstractmethod
    async def _load_batch(self, ctx: SdkTaskContext, batch: list[R]) -> None:
        """Bulk-write a batch into the tenant's destination table."""

    async def _close_source(self, ctx: SdkTaskContext) -> None:
        """Release the source (close stream / drop temp file). Default no-op."""
        return None

    def batch_size(self) -> int:
        """Batch size; override to change. Default 1000, mirrors Java."""
        return self.DEFAULT_BATCH_SIZE

    @final
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        counts = SdkRowResult()
        opened = False
        try:
            await self._open_source(ctx)
            opened = True
            buf: list[R] = []
            size = self.batch_size()
            async for row in self._read_rows(ctx):
                buf.append(row)
                if len(buf) >= size:
                    await self._flush(ctx, buf, counts)
            if buf:
                await self._flush(ctx, buf, counts)
            return SdkTaskResult.success_with(
                output=counts.to_output(),
                message=f"imported {counts.success()} rows",
            )
        except Exception as ex:
            return SdkTaskResult.fail(
                HANDLER_ERROR_CODE,
                str(ex) or type(ex).__name__,
                cause=ex,
            )
        finally:
            if opened:
                # Cleanup failures are silenced so the main result isn't
                # replaced; the parent template's _cleanup hook logs.
                with contextlib.suppress(Exception):
                    await self._close_source(ctx)

    async def _flush(
        self,
        ctx: SdkTaskContext,
        buf: list[R],
        counts: SdkRowResult,
    ) -> None:
        await self._load_batch(ctx, buf)
        counts.add_success(len(buf))
        buf.clear()


__all__ = ["SdkAbstractImportHandler"]
