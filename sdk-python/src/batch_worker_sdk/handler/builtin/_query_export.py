"""Builtin query-export handler (ADR-036 Export shape).

Mirrors Java ``com.example.batch.sdk.handler.builtin.QueryExportHandler`` /
``QueryExportConfig`` — fixed query → delimited / json file stream. The
Python flavour delegates the *query execution* to a tenant hook
(:meth:`QueryExportHandler._query_rows`) so the handler stays
DB-driver-agnostic (asyncpg / psycopg / SQLAlchemy / arbitrary fetcher).

Streaming: rows are yielded one-at-a-time and written serially; chunk
flushing is amortized via :func:`asyncio.to_thread` so the asyncio loop
stays responsive on large exports.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from pathlib import Path
from typing import IO, Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from batch_worker_sdk.handler.builtin._delimited import DelimitedFormat, encode_line
from batch_worker_sdk.handler._base import SdkRowResult
from batch_worker_sdk.handler.handler import SdkTaskHandler  # noqa: F401 — protocol parity
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult

ExportFormat = Literal["csv", "jsonl"]
"""Supported sink formats."""


class QueryExportConfig(BaseModel):
    """Settings for :class:`QueryExportHandler`.

    Mirrors Java ``QueryExportConfig`` record. ``sql`` is fixed in config
    (never assembled from task parameters) to keep the SQL-injection
    surface zero — Java enforces the same.
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    task_type: str
    """Globally-unique task-type code registered with the platform."""

    sql: str
    """Fixed query string (never assembled from task parameters)."""

    output_path_param: str = "outputPath"
    """Key in ``ctx.parameters`` from which to read the output file path."""

    format: ExportFormat = "csv"
    """Sink file format."""

    delimited: DelimitedFormat = Field(default_factory=DelimitedFormat.defaults)
    """CSV-only: delimiter / quote / header config."""

    chunk_size: int = Field(default=1000, gt=0)
    """Server-side cursor fetch size hint (mirrors Java ``fetchSize``)."""

    connection_ref: str | None = None
    """Opaque connection identifier (e.g. asyncpg DSN alias). Hook-defined semantics."""

    encoding: str = "utf-8"
    """Text encoding for the output file."""

    @classmethod
    def defaults(cls, task_type: str, sql: str) -> QueryExportConfig:
        return cls(task_type=task_type, sql=sql)


class QueryExportHandler:
    """Query → delimited / jsonl file export template.

    Tenant subclasses override :meth:`_query_rows` to plug their async DB
    cursor (asyncpg ``conn.cursor(sql).fetch(N)`` / SQLAlchemy
    ``async_engine.stream`` / etc.); the builtin owns file open, row
    serialization, header writes, counting, and cancellation polling.
    """

    def __init__(self, config: QueryExportConfig) -> None:
        self._config = config
        self._fh: IO[str] | None = None
        self._headers_written = False
        self._column_order: list[str] | None = None

    # -- SdkTaskHandler protocol --------------------------------------------------

    def task_type(self) -> str:
        return self._config.task_type

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        return None

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        counts = SdkRowResult()
        try:
            path = self._resolve_output_path(ctx)
        except (ValueError, FileNotFoundError) as ex:
            return SdkTaskResult.fail(
                "EXPORT_FAILED",
                f"query export failed: {ex}",
                cause=ex,
            )
        try:
            await self._open_destination(ctx, path)
            try:
                async for row in self._query_rows(ctx):
                    if (
                        ctx.cancel_signal is not None
                        and ctx.cancel_signal.is_cancellation_requested
                    ):
                        return SdkTaskResult.fail("CANCELLED", "cancelled by platform")
                    await self._write_row(ctx, row)
                    counts.inc_success()
            finally:
                await self._close_destination(ctx)
        except Exception as ex:
            return SdkTaskResult.fail(
                "EXPORT_FAILED",
                f"query export failed: {ex}",
                cause=ex,
            )
        output = counts.to_output()
        output["filePath"] = str(path)
        return SdkTaskResult.success_with(
            output=output,
            message=f"exported {counts.success()} rows",
        )

    # -- tenant-overridable hooks (mirror Java SdkAbstractExportHandler) ----------

    async def _open_destination(self, ctx: SdkTaskContext, path: Path) -> None:
        """Open the output file. Default opens text-mode write with the configured encoding."""
        self._fh = await asyncio.to_thread(self._open_text_file_write, path, self._config.encoding)
        self._headers_written = False
        self._column_order = None

    async def _query_rows(self, ctx: SdkTaskContext) -> AsyncIterator[dict[str, Any]]:
        """**Abstract for tenants.** Yield row dicts one at a time.

        Default raises :class:`NotImplementedError`. Tenants connect their
        async DB driver here; the builtin's whole point is the
        query → file boundary, so the *query* half is tenant-owned.
        """
        raise NotImplementedError(
            "QueryExportHandler subclasses must override _query_rows to plug their async DB driver"
        )
        # Unreachable but keeps the function an async generator:
        yield  # type: ignore[unreachable]

    async def _write_row(self, ctx: SdkTaskContext, row: dict[str, Any]) -> None:
        """Serialize one row to the open sink. Default dispatches on format."""
        if self._fh is None:
            raise RuntimeError("_open_destination must be called before _write_row")
        if self._config.format == "csv":
            await self._write_csv_row(row)
        elif self._config.format == "jsonl":
            await self._write_jsonl_row(row)
        else:  # pragma: no cover — pydantic Literal already rejects
            raise ValueError(f"unsupported export format: {self._config.format}")

    async def _close_destination(self, ctx: SdkTaskContext) -> None:
        """Flush and close the output file."""
        if self._fh is not None:
            fh = self._fh
            self._fh = None
            await asyncio.to_thread(self._flush_and_close, fh)

    # -- internals ----------------------------------------------------------------

    def _resolve_output_path(self, ctx: SdkTaskContext) -> Path:
        raw = ctx.parameters.get(self._config.output_path_param)
        if not isinstance(raw, str) or not raw.strip():
            raise ValueError(
                f"missing required parameter '{self._config.output_path_param}' (output path)"
            )
        path = Path(raw)
        parent = path.parent
        if str(parent) and not parent.exists():
            raise FileNotFoundError(f"output directory does not exist: {parent}")
        return path

    @staticmethod
    def _open_text_file_write(path: Path, encoding: str) -> IO[str]:
        return path.open("w", encoding=encoding, newline="")

    @staticmethod
    def _flush_and_close(fh: IO[str]) -> None:
        fh.flush()
        fh.close()

    async def _write_csv_row(self, row: dict[str, Any]) -> None:
        assert self._fh is not None
        fmt = self._config.delimited
        if self._column_order is None:
            self._column_order = list(row.keys())
            if fmt.header:
                header_line = encode_line(self._column_order, fmt.delimiter, fmt.quote)
                await asyncio.to_thread(self._fh.write, header_line + "\n")
                self._headers_written = True
        values = ["" if row.get(c) is None else str(row.get(c)) for c in self._column_order]
        line = encode_line(values, fmt.delimiter, fmt.quote)
        await asyncio.to_thread(self._fh.write, line + "\n")

    async def _write_jsonl_row(self, row: dict[str, Any]) -> None:
        assert self._fh is not None
        line = json.dumps(row, ensure_ascii=False, default=str) + "\n"
        await asyncio.to_thread(self._fh.write, line)
