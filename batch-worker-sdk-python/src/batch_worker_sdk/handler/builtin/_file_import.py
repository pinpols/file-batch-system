"""内置文件导入 handler(ADR-036 Import 形态)。

对齐 Java ``com.example.batch.sdk.handler.builtin.FileImportHandler`` /
``FileImportConfig``。Java 实现面向 JDBC(file → table);Python 版保持 sink
无关 —— 把源文件解析成行 dict,实际 load 委托给租户可覆盖的
:meth:`FileImportHandler._load_batch` 钩子。租户继承后插自己的目的端
(asyncpg / SQLAlchemy / HTTP API 等);内置只负责 I/O + 格式 + 批量边界循环。

Java 的 ``FileImportHandler`` 直接继承 ``SdkAbstractTaskHandler``;Python 版
为了对齐继承结构性 :class:`SdkTaskHandler` 协议。等 ``SdkAbstractImportHandler``
ABC 那条线落地后,本类会切到 ABC 上(钩子方法名 —— ``_open_source`` /
``_read_rows`` / ``_load_batch`` / ``_close_source`` —— 已经匹配 ABC 契约)。
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
"""支持的输入格式。Java 仅覆盖 ``csv``;Python 额外支持 ``json`` / ``jsonl``。"""


class FileImportConfig(BaseModel):
    """:class:`FileImportHandler` 的打开 + 解析配置。

    对齐 Java ``FileImportConfig`` record。``columns`` 列表(Java 用于 INSERT
    代码生成的必填项)在 Python 里可选,因为 load sink 由租户自定义;当 columns
    非空且 ``format == 'csv'`` 时,解析器会校验每行的字段数严格等于该长度
    (对齐 Java 的逐行校验行为)。
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    task_type: str
    """注册到平台、全局唯一的 task type code。"""

    file_path_param: str = "filePath"
    """从 ``ctx.parameters`` 取输入文件路径所用的 key。"""

    format: FileFormat = "csv"
    """源文件格式:``csv`` / ``json`` / ``jsonl``。"""

    delimited: DelimitedFormat = Field(default_factory=DelimitedFormat.defaults)
    """仅 CSV:分隔符 / 引号 / header 配置(json / jsonl 忽略)。"""

    columns: list[str] = Field(default_factory=list)
    """可选的 CSV 列名列表(按文件顺序)。为空 = 不做逐行列数校验。"""

    encoding: str = "utf-8"
    """源文件文本编码。默认 UTF-8。"""

    batch_size: int = Field(default=500, gt=0)
    """累积多少解析行后调用一次 ``_load_batch``。"""

    @classmethod
    def defaults(cls, task_type: str) -> FileImportConfig:
        """对齐 Java ``FileImportConfig.defaults`` —— 逗号 CSV、batch=500。"""
        return cls(task_type=task_type)


class FileImportHandler:
    """File → 租户 sink 的导入模板。

    租户子类覆盖 :meth:`_load_batch`(可选覆盖 :meth:`_open_source` /
    :meth:`_close_source`)插入自己的目的端。内置负责打开文件、格式解析、批次
    累积、取消轮询和行计数。

    原生异步:文件 I/O 通过 :func:`asyncio.to_thread` 派发,避免大文件阻塞
    event loop。
    """

    def __init__(self, config: FileImportConfig) -> None:
        self._config = config
        # 单任务文件句柄。Handler 在单个 asyncio task 内一次只处理一个任务;
        # 派发器要么为每个任务分配新实例,要么把实例视为跨任务可重入。我们
        # 只在 ``_open_source`` 与 ``_close_source`` 之间把句柄挂在实例上 ——
        # 与 Java 用局部变量的形状一致。
        self._fh: IO[str] | None = None

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

    # -- 租户可覆盖钩子(对齐 Java SdkAbstractImportHandler) --------------------

    async def _open_source(self, ctx: SdkTaskContext) -> None:
        """打开源文件。默认:从 ``ctx.parameters`` 解析路径,以读文本模式打开。"""
        path = self._resolve_path(ctx)
        self._fh = await asyncio.to_thread(self._open_text_file, path, self._config.encoding)

    async def _read_rows(self, ctx: SdkTaskContext) -> AsyncIterator[dict[str, Any]]:
        """逐行 yield 解析后的行 dict。默认:按 ``config.format`` 分派。"""
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
        else:  # pragma: no cover — pydantic Literal 已经拦截
            raise ValueError(f"unsupported format: {fmt}")

    async def _load_batch(self, ctx: SdkTaskContext, batch: list[dict[str, Any]]) -> None:
        """**租户必须覆盖的抽象方法。** 将一批解析后的行 load 到目的端。

        默认抛 :class:`NotImplementedError` —— 该内置的核心就是 file → 租户
        sink 的形态,而 sink 是租户领域内的事(asyncpg / SQLAlchemy / 消息总线
        / HTTP)。
        """
        raise NotImplementedError(
            "FileImportHandler subclasses must override _load_batch to define the import sink"
        )

    async def _close_source(self, ctx: SdkTaskContext) -> None:
        """关闭源文件。默认关闭 :meth:`_open_source` 打开的句柄。"""
        if self._fh is not None:
            fh = self._fh
            self._fh = None
            await asyncio.to_thread(fh.close)

    # -- 内部方法 ----------------------------------------------------------------

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
