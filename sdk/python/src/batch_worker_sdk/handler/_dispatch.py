"""Dispatch 形态 —— 租户 -> 外部推送(ADR-036)。

对齐 Java ``SdkAbstractDispatchHandler``:扇出推送到多个外部 target
(HTTP / SFTP / 消息队列)。单 target 失败计入 ``failed`` 并**不**中止
批次 —— 语义与 Java ``SdkAbstractTypedDispatchHandler`` 模板循环一致。

模板执行序::

    _resolve_targets (Target 的异步迭代器) ->
    _dispatch_to_target (单 target;抛异常 -> failed++)
"""

from __future__ import annotations

import logging
from abc import abstractmethod
from collections.abc import AsyncIterator
from typing import Any, final

from batch_worker_sdk.handler._base import (
    HANDLER_ERROR_CODE,
    SdkAbstractTaskHandler,
    SdkRowResult,
)
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult

logger = logging.getLogger(__name__)

DispatchResult = Any  # 每个 target 的不透明响应


class SdkAbstractDispatchHandler[T](SdkAbstractTaskHandler):
    """扇出推送到多个外部 target。

    对齐 Java ``SdkAbstractDispatchHandler<R>``。Python 版把 Java 的 4 钩子
    (``selectPayload`` / ``buildRequest`` / ``push`` / ``onResponse``)
    合并成 2 钩子契约(``_resolve_targets`` / ``_dispatch_to_target``)——
    需要更细粒度的租户在自己的 ``_dispatch_to_target`` 内部分层即可。
    扇出语义(逐 target catch、failed++、继续)与 Java 1:1 一致。
    """

    @abstractmethod
    def _resolve_targets(self, ctx: SdkTaskContext) -> AsyncIterator[T]:
        """返回 target / payload 项的异步迭代器。"""

    @abstractmethod
    async def _dispatch_to_target(
        self,
        ctx: SdkTaskContext,
        target: T,
    ) -> DispatchResult:
        """推送到单个 target。抛异常即本 target 计为失败。"""

    @final
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        counts = SdkRowResult()
        try:
            async for target in self._resolve_targets(ctx):
                try:
                    await self._dispatch_to_target(ctx, target)
                    counts.inc_success()
                except Exception as item_ex:
                    counts.inc_failed()
                    logger.warning("dispatch item failed: %s", item_ex)
            return SdkTaskResult.success_with(
                output=counts.to_output(),
                message=f"dispatched {counts.success()}/{counts.total()}",
            )
        except Exception as ex:
            return SdkTaskResult.fail(
                HANDLER_ERROR_CODE,
                str(ex) or type(ex).__name__,
                cause=ex,
            )


__all__ = ["DispatchResult", "SdkAbstractDispatchHandler"]
