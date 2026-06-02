"""Atomic 形态 —— 单次调用 handler 模板(ADR-036)。

对齐 Java ``SdkAbstractAtomicHandler``:单次原子调用(shell / 单条 SQL /
HTTP / 纯计算)。子类只实现 :meth:`_do_invoke`;异常由父模板捕获并转成
:meth:`SdkTaskResult.fail`,租户代码不必自己拼装结果对象。
"""

from __future__ import annotations

from abc import abstractmethod
from typing import Any, final

from batch_worker_sdk.handler._base import (
    HANDLER_ERROR_CODE,
    SdkAbstractTaskHandler,
)
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult


class SdkAbstractAtomicHandler[R](SdkAbstractTaskHandler):
    """单次原子调用 handler。

    对齐 Java ``SdkAbstractAtomicHandler<R>``。模板一次性包裹
    :meth:`_do_invoke`,把返回值经 :meth:`as_output` 装进
    :meth:`SdkTaskResult.success_with`。任意异常转为错误码
    ``HANDLER_ERROR`` 的失败结果。
    """

    @abstractmethod
    async def _do_invoke(self, ctx: SdkTaskContext) -> R:
        """租户实现的单次原子调用。"""

    def as_output(self, result: R | None) -> dict[str, Any]:
        """把调用返回值映射到 ``output`` dict。

        默认:非 None 返回 ``{"result": result}``,否则 ``{}`` ——
        与 Java ``asOutput`` 一致。
        """
        if result is None:
            return {}
        return {"result": result}

    @final
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        try:
            return SdkTaskResult.success_with(
                output=self.as_output(await self._do_invoke(ctx)),
                message="invoked",
            )
        except Exception as ex:
            return SdkTaskResult.fail(
                HANDLER_ERROR_CODE,
                str(ex) or type(ex).__name__,
                cause=ex,
            )


__all__ = ["SdkAbstractAtomicHandler"]
