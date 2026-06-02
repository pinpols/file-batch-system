"""Builtin file-import handler (ADR-036 Import shape).

Mirrors Java ``com.example.batch.sdk.handler.builtin.FileImportHandler`` /
``FileImportConfig``. The Java implementation is JDBC-targeted (file →
table); the Python flavour stays sink-agnostic — it parses the source
file into row dicts and delegates the actual load to a tenant-overridable
:meth:`FileImportHandler._load_batch` hook. Tenants subclass and plug
their own destination (asyncpg, SQLAlchemy, HTTP API, etc.); the builtin
handles the I/O + format + batch boundary loop.

Java's ``FileImportHandler`` extends ``SdkAbstractTaskHandler`` directly;
the Python form extends the structural :class:`SdkTaskHandler` protocol
for parity. When the in-flight ``SdkAbstractImportHandler`` ABC lane
lands, this class will rebase onto it (the public hook names —
``_open_source`` / ``_read_rows`` / ``_load_batch`` / ``_close_source`` —
already match that ABC's contract).
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from pathlib import Path
from typing import IO, Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from batch_worker_sdk.handler._base import SdkRowResult
from batch_worker_sdk.handler.builtin._delimited import DelimitedFormat, parse_line
from batch_worker_sdk.handler.handler import SdkTaskHandler  # noqa: F401 — protocol parity
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult

FileFormat = Literal["csv", "json", "jsonl"]
"""Supported input formats. Java covers ``csv`` only; Python adds ``json`` / ``jsonl``."""


class FileImportConfig(BaseModel):
    """Open-and-parse settings for :class:`FileImportHandler`.

    Mirrors Java ``FileImportConfig`` record. The ``columns`` list (Java
    required for INSERT codegen) is optional here because the Python load
    sink is tenant-defined; when present and ``format == 'csv'`` the
    parser asserts each row has exactly that many fields (matches Java
    line-validation behaviour).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    task_type: str
    """Globally-unique task-type code registered with the platform."""

    file_path_param: str = "filePath"
    """Key in ``ctx.parameters`` from which to read the input file path."""

    format: FileFormat = "csv"
    """Source file format: ``csv`` / ``json`` / ``jsonl``."""

    delimited: DelimitedFormat = Field(default_factory=DelimitedFormat.defaults)
    """CSV-only: delimiter / quote / header config (ignored for json/jsonl)."""

    columns: list[str] = Field(default_factory=list)
    """Optional CSV column names (in file order). Empty = no per-row column count check."""

    encoding: str = "utf-8"
    """Text encoding for the source file. Default UTF-8."""

    batch_size: int = Field(default=500, gt=0)
    """How many parsed rows to accumulate before calling ``_load_batch``."""

    @classmethod
    def defaults(cls, task_type: str) -> FileImportConfig:
        """Mirror of Java ``FileImportConfig.defaults`` — comma-CSV, batch=500."""
        return cls(task_type=task_type)


class FileImportHandler:
    """File → tenant-sink import template.

    Tenant subclasses override :meth:`_load_batch` (and optionally
    :meth:`_open_source` / :meth:`_close_source`) to plug their own
    destination. The builtin handles file open, format parsing, batch
    accumulation, cancellation polling, and row counting.

    Async-native: file I/O is dispatched via :func:`asyncio.to_thread` to
    avoid blocking the event loop on large files.
    """

    def __init__(self, config: FileImportConfig) -> None:
        self._config = config
        # Per-task file handle. The handler is single-task-at-a-time within
        # a single asyncio task; the dispatcher allocates a fresh instance
        # (or treats the instance as reentrant-safe across tasks). We
        # park the handle on the instance only between ``_open_source``
        # and ``_close_source`` — same shape as Java's local-var approach.
        self._fh: IO[str] | None = None

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
            await self._open_source(ctx)
            try:
                batch: list[dict[str, Any]] = []
                async for row in self._read_rows(ctx):
                    if (
                        ctx.cancel_signal is not None
                        and ctx.cancel_signal.is_cancellation_requested
                    ):
                        return SdkTaskResult.fail("CANCELLED", "cancelled by platform")
                    batch.append(row)
                    counts.inc_success()
                    if len(batch) >= self._config.batch_size:
                        await self._load_batch(ctx, batch)
                        batch = []
                if batch:
                    await self._load_batch(ctx, batch)
            finally:
                await self._close_source(ctx)
        except Exception as ex:
            return SdkTaskResult.fail(
                "IMPORT_FAILED",
                f"file import failed: {ex}",
                cause=ex,
            )
        return SdkTaskResult.success_with(
            output=counts.to_output(),
            message=f"imported {counts.success()} rows",
        )

    # -- tenant-overridable hooks (mirror Java SdkAbstractImportHandler) ----------

    async def _open_source(self, ctx: SdkTaskContext) -> None:
        """Open the source file. Default: resolves path from ``ctx.parameters`` and opens read-text."""
        path = self._resolve_path(ctx)
        self._fh = await asyncio.to_thread(self._open_text_file, path, self._config.encoding)

    async def _read_rows(self, ctx: SdkTaskContext) -> AsyncIterator[dict[str, Any]]:
        """Yield parsed row dicts. Default: dispatches on ``config.format``."""
        if self._fh is None:
            raise RuntimeError("_open_source must be called before _read_rows")
        fmt = self._config.format
        if fmt == "csv":
            async for row in self._read_csv():
                yield row
        elif fmt == "jsonl":
            async for row in self._read_jsonl():
                yield row
        elif fmt == "json":
            async for row in self._read_json():
                yield row
        else:  # pragma: no cover — pydantic Literal already rejects
            raise ValueError(f"unsupported format: {fmt}")

    async def _load_batch(self, ctx: SdkTaskContext, batch: list[dict[str, Any]]) -> None:
        """**Abstract for tenants.** Load one batch of parsed rows into the destination.

        Default raises :class:`NotImplementedError` — the whole point of
        this builtin is the file → tenant-sink shape, where the sink is
        the tenant's domain (asyncpg / SQLAlchemy / message bus / HTTP).
        """
        raise NotImplementedError(
            "FileImportHandler subclasses must override _load_batch to define the import sink"
        )

    async def _close_source(self, ctx: SdkTaskContext) -> None:
        """Close the source file. Default closes the handle opened by :meth:`_open_source`."""
        if self._fh is not None:
            fh = self._fh
            self._fh = None
            await asyncio.to_thread(fh.close)

    # -- internals ----------------------------------------------------------------

    def _resolve_path(self, ctx: SdkTaskContext) -> Path:
        raw = ctx.parameters.get(self._config.file_path_param)
        if not isinstance(raw, str) or not raw.strip():
            raise ValueError(
                f"missing required parameter '{self._config.file_path_param}' (file path)"
            )
        path = Path(raw)
        if not path.is_file():
            raise FileNotFoundError(f"file not readable: {raw}")
        return path

    @staticmethod
    def _open_text_file(path: Path, encoding: str) -> IO[str]:
        return path.open("r", encoding=encoding, newline="")

    async def _read_csv(self) -> AsyncIterator[dict[str, Any]]:
        assert self._fh is not None
        fmt = self._config.delimited
        cols = self._config.columns
        header_consumed = False
        first_line_header_names: list[str] | None = None
        line_no = 0
        while True:
            line = await asyncio.to_thread(self._fh.readline)
            if not line:
                return
            line_no += 1
            stripped = line.rstrip("\r\n")
            if not stripped.strip():
                continue
            if fmt.header and not header_consumed:
                header_consumed = True
                first_line_header_names = parse_line(stripped, fmt.delimiter, fmt.quote)
                continue
            fields = parse_line(stripped, fmt.delimiter, fmt.quote)
            row_cols = cols or first_line_header_names or [f"col{i}" for i in range(len(fields))]
            if cols and len(fields) != len(cols):
                raise ValueError(f"line {line_no}: expected {len(cols)} fields, got {len(fields)}")
            yield {
                row_cols[i] if i < len(row_cols) else f"col{i}": fields[i]
                for i in range(len(fields))
            }

    async def _read_jsonl(self) -> AsyncIterator[dict[str, Any]]:
        assert self._fh is not None
        while True:
            line = await asyncio.to_thread(self._fh.readline)
            if not line:
                return
            stripped = line.strip()
            if not stripped:
                continue
            obj = json.loads(stripped)
            if not isinstance(obj, dict):
                raise ValueError(f"jsonl row must be a JSON object, got {type(obj).__name__}")
            yield obj

    async def _read_json(self) -> AsyncIterator[dict[str, Any]]:
        assert self._fh is not None
        raw = await asyncio.to_thread(self._fh.read)
        if not raw.strip():
            return
        data = json.loads(raw)
        if not isinstance(data, list):
            raise ValueError(
                f"json source must be a top-level array of objects, got {type(data).__name__}"
            )
        for i, obj in enumerate(data):
            if not isinstance(obj, dict):
                raise ValueError(f"json row {i}: expected object, got {type(obj).__name__}")
            yield obj
