"""Export 形态 —— 租户 -> 外部 模板(ADR-036)。

对齐 Java ``SdkAbstractExportHandler``:租户库 -> 文件(或其他 sink)。
模板执行序::

    _open_destination -> _query_rows (异步迭代器) ->
    _write_row (逐行) -> (finally) _close_destination
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
    """租户库 -> 文件 / 外部 sink 的导出形态。

    对齐 Java ``SdkAbstractExportHandler<R>``。Java 的 ``Stream<R>
    streamRows`` 在 Python 里改为 :class:`typing.AsyncIterator`;
    行格式化与 sink 的 open/close 对称都是异步方法。
    """

    async def _open_destination(self, ctx: SdkTaskContext) -> None:
        """打开输出 sink(建文件 / S3 multipart / writer)。默认空实现。"""
        return None

    @abstractmethod
    def _query_rows(self, ctx: SdkTaskContext) -> AsyncIterator[R]:
        """返回源行的异步迭代器。"""

    @abstractmethod
    async def _write_row(self, ctx: SdkTaskContext, row: R) -> None:
        """格式化并写出单行到 sink。"""

    async def _close_destination(self, ctx: SdkTaskContext) -> None:
        """flush / close / 上传。默认空实现。"""
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
                # 清理失败静默,避免覆盖主结果。
                with contextlib.suppress(Exception):
                    await self._close_destination(ctx)


__all__ = ["SdkAbstractExportHandler"]
