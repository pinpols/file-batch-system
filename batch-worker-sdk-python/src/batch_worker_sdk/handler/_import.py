"""Import 形态 —— 外部 -> 租户 模板(ADR-036)。

对齐 Java ``SdkAbstractImportHandler``:文件/流 -> 租户库。
模板执行序::

    _open_source -> _read_rows (异步迭代器) -> 批量缓冲 ->
    _load_batch -> (finally) _close_source

子类必须实现 3 个钩子(``_read_rows`` / ``_load_batch``);其余有安全默认值。
每个形态的 ``_do_execute`` 是 :func:`final`。
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
    """文件 / 流 -> 租户库的导入形态。

    对齐 Java ``SdkAbstractImportHandler<R>``。Java 用 ``Stream<R>`` 暴露
    ``readRows``;Python 改用 :class:`typing.AsyncIterator`(异步生成器),
    因为 SDK 的 I/O 表面仅异步。
    """

    DEFAULT_BATCH_SIZE: int = 1000

    async def _open_source(self, ctx: SdkTaskContext) -> None:
        """打开数据源(连 SFTP / 下载文件 / 打开流)。默认空实现。"""
        return None

    @abstractmethod
    def _read_rows(self, ctx: SdkTaskContext) -> AsyncIterator[R]:
        """返回已解析行的异步迭代器(每条记录一行)。

        对齐 Java ``Stream<R> readRows(...)``。实现一般写成
        ``async def`` 生成器。
        """

    @abstractmethod
    async def _load_batch(self, ctx: SdkTaskContext, batch: list[R]) -> None:
        """批量写入租户目标表。"""

    async def _close_source(self, ctx: SdkTaskContext) -> None:
        """释放数据源(关流 / 删临时文件)。默认空实现。"""
        return None

    def batch_size(self) -> int:
        """批大小;重写可调整。默认 1000,与 Java 一致。"""
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
                # 清理失败静默,避免覆盖主结果;
                # 父模板的 _cleanup 钩子会记录日志。
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
