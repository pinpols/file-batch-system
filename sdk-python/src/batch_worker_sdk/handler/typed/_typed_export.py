"""Typed Export template — Java parity:
``SdkAbstractTypedExportHandler<I, O, R>``.

Template order: ``open_sink -> build_query -> stream_rows -> format_row
(per row) -> write_out (finalize)``. Strongly-typed pydantic params in,
optional pydantic ``OutputT`` out (None -> counter-output fallback).
``write_out`` may return an explicit :class:`SdkTaskResult` that wins
over both ``summarize`` and the counter (matches Java precedence).
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


class SdkAbstractTypedExportHandler[ParamsT: BaseModel, OutputT: BaseModel, RowT](
    SdkAbstractTaskHandler
):
    """Typed Export handler — tenant DB -> external file."""

    _params_model: type[BaseModel] | None = None

    def __init_subclass__(cls, **kwargs: object) -> None:
        super().__init_subclass__(**kwargs)
        if cls._params_model is None:
            cls._params_model = _resolve_params_model(cls, SdkAbstractTypedExportHandler)

    # ---- tenant hooks ----------------------------------------------------

    def open_sink(self, params: ParamsT, ctx: SdkTaskContext) -> None:
        return None

    @abstractmethod
    def build_query(self, params: ParamsT, ctx: SdkTaskContext) -> str: ...

    @abstractmethod
    def stream_rows(
        self, params: ParamsT, ctx: SdkTaskContext, query: str
    ) -> Iterable[RowT] | Iterator[RowT] | AsyncIterator[RowT]: ...

    @abstractmethod
    def format_row(self, params: ParamsT, ctx: SdkTaskContext, row: RowT) -> None: ...

    def write_out(
        self, params: ParamsT, ctx: SdkTaskContext, counts: SdkRowResult
    ) -> SdkTaskResult | None:
        """Finalize the sink. ``None`` -> fall through to summarize/counter."""
        return None

    def summarize(self, params: ParamsT, counts: SdkRowResult) -> OutputT | None:
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
        self.open_sink(params, ctx)
        q = self.build_query(params, ctx)
        counts = SdkRowResult()
        rows = self.stream_rows(params, ctx, q)
        if hasattr(rows, "__aiter__"):
            async for row in rows:
                self.format_row(params, ctx, row)
                counts.inc_success()
        else:
            for row in rows:
                self.format_row(params, ctx, row)
                counts.inc_success()
        explicit = self.write_out(params, ctx, counts)
        if explicit is not None:
            return explicit
        return self._result(params, counts, f"exported {counts.success} rows")

    def _result(self, params: ParamsT, counts: SdkRowResult, default_message: str) -> SdkTaskResult:
        output = self.summarize(params, counts)
        if output is None:
            return SdkTaskResult.success_with(output=counts.to_output(), message=default_message)
        return SdkTaskResult.success_with(
            output=SdkTypedParameters.serialize(output), message=default_message
        )
