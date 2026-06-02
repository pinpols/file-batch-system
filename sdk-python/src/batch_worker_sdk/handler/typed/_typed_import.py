"""Typed Import 模板 —— 对齐 Java
``SdkAbstractTypedImportHandler<I, O, R>``。

模板顺序:``open_source -> read_rows(流式) -> load_batch(批量 flush)
-> summarize``。强类型输入 ``ParamsT``(pydantic)取代 Java 的 Jackson 反射
解析;强类型业务结果 ``OutputT`` 序列化回 ``SdkTaskResult.output``
(``None`` -> 行计数器 map 兜底,对齐 Java)。
"""

from __future__ import annotations

import logging
from abc import abstractmethod
from collections.abc import AsyncIterator, Iterable, Iterator
from typing import get_args, get_origin

from pydantic import BaseModel

from batch_worker_sdk.handler._base import SdkAbstractTaskHandler, SdkRowResult
from batch_worker_sdk.handler.typed._typed_parameters import SdkTypedParameters
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult

_log = logging.getLogger(__name__)


def _resolve_params_model(cls: type, generic_base: type) -> type[BaseModel] | None:
    for klass in cls.__mro__:
        for base in getattr(klass, "__orig_bases__", ()):
            if get_origin(base) is generic_base:
                args = get_args(base)
                if args and isinstance(args[0], type) and issubclass(args[0], BaseModel):
                    return args[0]
    return None


class SdkAbstractTypedImportHandler[ParamsT: BaseModel, OutputT: BaseModel, RowT](
    SdkAbstractTaskHandler
):
    """Typed Import handler —— 租户 -> 租户 DB;行级流式 + 批量 flush。"""

    _params_model: type[BaseModel] | None = None
    DEFAULT_BATCH_SIZE = 1000

    def __init_subclass__(cls, **kwargs: object) -> None:
        super().__init_subclass__(**kwargs)
        if cls._params_model is None:
            cls._params_model = _resolve_params_model(cls, SdkAbstractTypedImportHandler)

    # ---- 租户钩子 --------------------------------------------------------

    def open_source(self, params: ParamsT, ctx: SdkTaskContext) -> None:
        """打开数据源(默认 no-op)。"""
        return None

    @abstractmethod
    def read_rows(
        self, params: ParamsT, ctx: SdkTaskContext
    ) -> Iterable[RowT] | Iterator[RowT] | AsyncIterator[RowT]:
        """返回行的 iterable / async iterator。"""
        ...

    @abstractmethod
    def load_batch(self, params: ParamsT, ctx: SdkTaskContext, batch: list[RowT]) -> None:
        """把一批数据写入租户目标表。"""
        ...

    def batch_size(self) -> int:
        return self.DEFAULT_BATCH_SIZE

    def summarize(self, params: ParamsT, counts: SdkRowResult) -> OutputT | None:
        return None

    # ---- 模板 ------------------------------------------------------------

    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        model = self._params_model
        if model is None:
            return SdkTaskResult.fail(
                "TYPED_PARAMS_UNRESOLVED",
                f"{type(self).__name__}: ParamsT must be closed on a pydantic BaseModel",
            )
        try:
            params: ParamsT = SdkTypedParameters.parse(ctx.parameters, model)  # type: ignore[assignment]
        except ValueError as ex:
            return SdkTaskResult.fail(
                "INVALID_TYPED_PARAMS",
                f"invalid parameters for taskType={self.task_type()}: {ex}",
                cause=ex,
            )
        self.open_source(params, ctx)
        counts = SdkRowResult()
        buf: list[RowT] = []
        size = self.batch_size()
        rows = self.read_rows(params, ctx)
        if hasattr(rows, "__aiter__"):
            async for row in rows:
                buf.append(row)
                if len(buf) >= size:
                    self._flush(params, ctx, buf, counts)
        else:
            for row in rows:
                buf.append(row)
                if len(buf) >= size:
                    self._flush(params, ctx, buf, counts)
        if buf:
            self._flush(params, ctx, buf, counts)
        return self._result(params, counts, f"imported {counts.success()} rows")

    def _flush(
        self,
        params: ParamsT,
        ctx: SdkTaskContext,
        buf: list[RowT],
        counts: SdkRowResult,
    ) -> None:
        self.load_batch(params, ctx, buf)
        counts.add_success(len(buf))
        buf.clear()

    def _result(self, params: ParamsT, counts: SdkRowResult, default_message: str) -> SdkTaskResult:
        output = self.summarize(params, counts)
        if output is None:
            return SdkTaskResult.success_with(output=counts.to_output(), message=default_message)
        return SdkTaskResult.success_with(
            output=SdkTypedParameters.serialize(output), message=default_message
        )
