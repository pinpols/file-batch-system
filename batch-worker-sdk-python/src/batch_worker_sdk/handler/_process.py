"""Process 形态 —— 租户 -> 租户 转换流水线(ADR-036)。

对齐 Java ``SdkAbstractProcessHandler<I, O>``。模板执行序::

    _open_input (InputRow 异步迭代器) -> _transform (逐行) ->
    _write_output (逐 OutputRow)

``_transform`` 返回 ``None`` 时该行标记为 ``skipped``(与 Java 语义一致)。
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
    """租户库 -> 租户库 的转换流水线。

    对齐 Java ``SdkAbstractProcessHandler<I, O>``。Python 基类把
    "逐行写" 与 "批量写" 的拆分合并为单一 ``_write_output(ctx, output)``
    钩子 —— Java typed 基类做缓冲与批量 upsert;Python 这边需要的话
    在自己的 ``_write_output`` 里缓冲即可。(形态契约 —— transform 返
    null = 跳过、逐行 success/skipped 计数 —— 与 Java 1:1 一致。)
    """

    @abstractmethod
    def _open_input(self, ctx: SdkTaskContext) -> AsyncIterator[InputRow]:
        """返回输入行的异步迭代器。"""

    @abstractmethod
    async def _transform(self, ctx: SdkTaskContext, input_row: InputRow) -> OutputRow | None:
        """把一条输入行转换为一条输出行。

        返回 ``None`` 时该行标记为 ``skipped``(不写出)。
        """

    @abstractmethod
    async def _write_output(self, ctx: SdkTaskContext, output_row: OutputRow) -> None:
        """持久化单条输出行。"""

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
