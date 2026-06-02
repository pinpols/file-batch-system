"""Typed Process template — Java parity:
``SdkAbstractTypedProcessHandler<P, IN, OUT, O>``.

Template order: ``select_input -> transform (per row; None -> skip)
-> upsert (batched) -> summarize``. Strongly-typed pydantic params,
weakly-typed in/out row types (``InRowT`` / ``OutRowT`` are tenant
domain types — rarely pydantic, often dicts or dataclasses, kept
parametric).
"""

from __future__ import annotations

from abc import abstractmethod
from collections.abc import AsyncIterator, Iterable, Iterator
from typing import get_args, get_origin

from pydantic import BaseModel

from batch_worker_sdk.handler._base import SdkAbstractTaskHandler, SdkRowResult
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


class SdkAbstractTypedProcessHandler[
    ParamsT: BaseModel, InRowT, OutRowT, OutputT: BaseModel
](SdkAbstractTaskHandler):
    """Typed Process handler — tenant -> tenant (transform & write back)."""

    _params_model: type[BaseModel] | None = None
    DEFAULT_BATCH_SIZE = 500

    def __init_subclass__(cls, **kwargs: object) -> None:
        super().__init_subclass__(**kwargs)
        if cls._params_model is None:
            cls._params_model = _resolve_params_model(cls, SdkAbstractTypedProcessHandler)

    # ---- tenant hooks ----------------------------------------------------

    @abstractmethod
    def select_input(
        self, params: ParamsT, ctx: SdkTaskContext
    ) -> Iterable[InRowT] | Iterator[InRowT] | AsyncIterator[InRowT]: ...

    @abstractmethod
    def transform(
        self, params: ParamsT, ctx: SdkTaskContext, row: InRowT
    ) -> OutRowT | None:
        """Return ``None`` to skip the row (counts as ``skipped``)."""
        ...

    @abstractmethod
    def upsert(
        self, params: ParamsT, ctx: SdkTaskContext, batch: list[OutRowT]
    ) -> None: ...

    def batch_size(self) -> int:
        return self.DEFAULT_BATCH_SIZE

    def summarize(
        self, params: ParamsT, counts: SdkRowResult
    ) -> OutputT | None:
        return None

    # ---- template --------------------------------------------------------

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
        counts = SdkRowResult()
        buf: list[OutRowT] = []
        size = self.batch_size()
        rows = self.select_input(params, ctx)

        def _drain() -> None:
            if buf:
                self.upsert(params, ctx, buf)
                buf.clear()

        if hasattr(rows, "__aiter__"):
            async for row in rows:
                out = self.transform(params, ctx, row)
                if out is not None:
                    buf.append(out)
                    counts.inc_success()
                else:
                    counts.inc_skipped()
                if len(buf) >= size:
                    _drain()
        else:
            for row in rows:
                out = self.transform(params, ctx, row)
                if out is not None:
                    buf.append(out)
                    counts.inc_success()
                else:
                    counts.inc_skipped()
                if len(buf) >= size:
                    _drain()
        _drain()
        return self._result(params, counts, f"processed {counts.success} rows")

    def _result(
        self, params: ParamsT, counts: SdkRowResult, default_message: str
    ) -> SdkTaskResult:
        output = self.summarize(params, counts)
        if output is None:
            return SdkTaskResult.success_with(output=counts.to_output(), message=default_message)
        return SdkTaskResult.success_with(
            output=SdkTypedParameters.serialize(output), message=default_message
        )
