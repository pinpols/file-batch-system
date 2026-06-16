"""Typed Process 模板 —— 对齐 Java
``SdkAbstractTypedProcessHandler<P, IN, OUT, O>``。

模板顺序:``select_input -> transform(逐行;None -> 跳过)
-> upsert(批量) -> summarize``。强类型 pydantic 入参,弱类型 in/out
行类型(``InRowT`` / ``OutRowT`` 是租户领域类型 —— 很少是 pydantic,
通常是 dict 或 dataclass,保持参数化)。
"""

from __future__ import annotations

from abc import abstractmethod
from collections.abc import AsyncIterator, Iterable, Iterator
from typing import get_args, get_origin

from pydantic import BaseModel

from batch_worker_sdk.handler._base import SdkAbstractTaskHandler, SdkRowResult
from batch_worker_sdk.handler.typed._resumable import ResumableTemplateMixin
from batch_worker_sdk.handler.typed._typed_parameters import SdkTypedParameters
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult


def _resolve_params_model(cls: type, generic_base: type) -> type[BaseModel] | None:
    for klass in cls.__mro__:
        for base in getattr(klass, "__orig_bases__", ()):
            if get_origin(base) is generic_base:
                args = get_args(base)
                if args and isinstance(args[0], type) and issubclass(args[0], BaseModel):
                    return args[0]
    return None


class SdkAbstractTypedProcessHandler[ParamsT: BaseModel, InRowT, OutRowT, OutputT: BaseModel](
    ResumableTemplateMixin, SdkAbstractTaskHandler
):
    """Typed Process handler —— 租户 -> 租户(变换并回写)。

    续跑(ADR-037):起头读断点、已完成跳过、恢复计数;每个 drain 批次走
    ``await ctx.commit(break_key)`` 原子提交 + 限流上报;取消在 commit 安全点抛
    :class:`SdkTaskStopped`,顶层落 cancelled 终态。
    """

    _params_model: type[BaseModel] | None = None
    DEFAULT_BATCH_SIZE = 500

    def __init_subclass__(cls, **kwargs: object) -> None:
        super().__init_subclass__(**kwargs)
        if cls._params_model is None:
            cls._params_model = _resolve_params_model(cls, SdkAbstractTypedProcessHandler)

    # ---- 租户钩子 --------------------------------------------------------

    @abstractmethod
    def select_input(
        self, params: ParamsT, ctx: SdkTaskContext
    ) -> Iterable[InRowT] | Iterator[InRowT] | AsyncIterator[InRowT]: ...

    @abstractmethod
    def transform(self, params: ParamsT, ctx: SdkTaskContext, row: InRowT) -> OutRowT | None:
        """返回 ``None`` 表示跳过该行(计入 ``skipped``)。"""
        ...

    @abstractmethod
    def upsert(self, params: ParamsT, ctx: SdkTaskContext, batch: list[OutRowT]) -> None: ...

    def batch_size(self) -> int:
        return self.DEFAULT_BATCH_SIZE

    def summarize(self, params: ParamsT, counts: SdkRowResult) -> OutputT | None:
        return None

    # ---- 模板 ------------------------------------------------------------

    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        return await self._guard(self._run(ctx))

    async def _run(self, ctx: SdkTaskContext) -> SdkTaskResult:
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

        # 续跑(决策一):已完成则跳过;否则恢复计数。
        resume = self._resume(ctx)
        if resume.already_completed:
            return self._result(
                params, resume.counts, f"resumed-complete: {resume.counts.success()} rows"
            )
        counts = resume.counts
        buf: list[OutRowT] = []
        size = self.batch_size()
        rows = self.select_input(params, ctx)

        async def _drain() -> None:
            if buf:
                self.upsert(params, ctx, buf)
                last_row = buf[-1]
                buf.clear()
                # 三合一可靠提交 + 取消安全点(决策二/三)。可能抛 SdkTaskStopped。
                await self._commit_batch(ctx, last_row, counts)

        async def _handle(row: InRowT) -> None:
            out = self.transform(params, ctx, row)
            if out is not None:
                buf.append(out)
                counts.inc_success()
            else:
                counts.inc_skipped()
            if len(buf) >= size:
                await _drain()

        if hasattr(rows, "__aiter__"):
            async for row in rows:
                await _handle(row)
        else:
            for row in rows:
                await _handle(row)
        await _drain()
        return self._result(params, counts, f"processed {counts.success()} rows")

    def _result(self, params: ParamsT, counts: SdkRowResult, default_message: str) -> SdkTaskResult:
        output = self.summarize(params, counts)
        if output is None:
            return SdkTaskResult.success_with(output=counts.to_output(), message=default_message)
        return SdkTaskResult.success_with(
            output=SdkTypedParameters.serialize(output), message=default_message
        )
