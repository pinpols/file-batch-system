"""内置查询导出 handler(ADR-036 Export 形态)。

对齐 Java ``com.example.batch.sdk.handler.builtin.QueryExportHandler`` /
``QueryExportConfig`` —— 固定查询 → 定界 / json 文件流。Python 版把 *查询
执行* 委托给租户钩子(:meth:`QueryExportHandler._query_rows`),让 handler
保持 DB 驱动无关(asyncpg / psycopg / SQLAlchemy / 任意自定义 fetcher)。

流式:逐行 yield 串行写出;chunk flush 通过 :func:`asyncio.to_thread` 摊销,
保证大导出时 asyncio loop 保持响应。
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from pathlib import Path
from typing import IO, Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from batch_worker_sdk.handler._base import SdkRowResult
from batch_worker_sdk.handler.builtin._delimited import DelimitedFormat, encode_line
from batch_worker_sdk.handler.handler import SdkTaskHandler  # noqa: F401 — protocol parity
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult

ExportFormat = Literal["csv", "jsonl"]
"""支持的 sink 文件格式。"""


class QueryExportConfig(BaseModel):
    """:class:`QueryExportHandler` 的配置。

    对齐 Java ``QueryExportConfig`` record。``sql`` 在配置中固定(从不从任务
    参数拼装)以把 SQL 注入面收敛到零 —— Java 同样规则。
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    task_type: str
    """注册到平台、全局唯一的 task type code。"""

    sql: str
    """固定查询串(从不从任务参数拼装)。"""

    output_path_param: str = "outputPath"
    """从 ``ctx.parameters`` 取输出文件路径所用的 key。"""

    format: ExportFormat = "csv"
    """Sink 文件格式。"""

    delimited: DelimitedFormat = Field(default_factory=DelimitedFormat.defaults)
    """仅 CSV:分隔符 / 引号 / header 配置。"""

    chunk_size: int = Field(default=1000, gt=0)
    """服务端游标 fetch size 提示(对齐 Java ``fetchSize``)。"""

    connection_ref: str | None = None
    """不透明的连接标识(例如 asyncpg DSN alias)。语义由钩子决定。"""

    encoding: str = "utf-8"
    """输出文件文本编码。"""

    @classmethod
    def defaults(cls, task_type: str, sql: str) -> QueryExportConfig:
        return cls(task_type=task_type, sql=sql)


class QueryExportHandler:
    """Query → 定界 / jsonl 文件导出模板。

    租户子类覆盖 :meth:`_query_rows` 插自己的异步 DB 游标(asyncpg
    ``conn.cursor(sql).fetch(N)`` / SQLAlchemy ``async_engine.stream`` 等);
    内置负责文件打开、行序列化、header 写入、计数和取消轮询。
    """

    def __init__(self, config: QueryExportConfig) -> None:
        self._config = config
        self._fh: IO[str] | None = None
        self._headers_written = False
        self._column_order: list[str] | None = None

    # -- SdkTaskHandler 协议 ------------------------------------------------------

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

    # -- 租户可覆盖钩子(对齐 Java SdkAbstractExportHandler) --------------------

    async def _open_destination(self, ctx: SdkTaskContext, path: Path) -> None:
        """打开输出文件。默认以配置编码的文本写模式打开。"""
        self._fh = await asyncio.to_thread(self._open_text_file_write, path, self._config.encoding)
        self._headers_written = False
        self._column_order = None

    async def _query_rows(self, ctx: SdkTaskContext) -> AsyncIterator[dict[str, Any]]:
        """**租户必须覆盖的抽象方法。** 逐行 yield 行 dict。

        默认抛 :class:`NotImplementedError`。租户在此接异步 DB 驱动;该内置
        的核心就是 query → 文件的边界,所以 *query* 那一半交给租户。
        """
        raise NotImplementedError(
            "QueryExportHandler subclasses must override _query_rows to plug their async DB driver"
        )
        # 不可达但保持本函数为 async generator:
        yield  # type: ignore[unreachable]

    async def _write_row(self, ctx: SdkTaskContext, row: dict[str, Any]) -> None:
        """将一行序列化到已打开的 sink。默认按 format 分派。"""
        if self._fh is None:
            raise RuntimeError("_open_destination must be called before _write_row")
        if self._config.format == "csv":
            await self._write_csv_row(row)
        elif self._config.format == "jsonl":
            await self._write_jsonl_row(row)
        else:  # pragma: no cover — pydantic Literal 已经拦截
            raise ValueError(f"unsupported export format: {self._config.format}")

    async def _close_destination(self, ctx: SdkTaskContext) -> None:
        """Flush 并关闭输出文件。"""
        if self._fh is not None:
            fh = self._fh
            self._fh = None
            await asyncio.to_thread(self._flush_and_close, fh)

    # -- 内部方法 ----------------------------------------------------------------

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
