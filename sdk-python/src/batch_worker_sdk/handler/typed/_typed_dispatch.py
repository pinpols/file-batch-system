"""Typed Dispatch template — Java parity:
``SdkAbstractTypedDispatchHandler<I, O, R>``.

Template order: ``select_payload -> build_request (per item) -> push
-> on_response``. Single-item failure increments ``failed`` and does
NOT abort the batch (matches Java's per-item ``try/catch -> incFailed``).
"""

from __future__ import annotations

import logging
from abc import abstractmethod
from typing import Any, get_args, get_origin

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


class SdkAbstractTypedDispatchHandler[ParamsT: BaseModel, OutputT: BaseModel, TargetT](
    SdkAbstractTaskHandler
):
    """Typed Dispatch handler — tenant -> external push (DB -> HTTP/SFTP)."""

    _params_model: type[BaseModel] | None = None

    def __init_subclass__(cls, **kwargs: object) -> None:
        super().__init_subclass__(**kwargs)
        if cls._params_model is None:
            cls._params_model = _resolve_params_model(cls, SdkAbstractTypedDispatchHandler)

    # ---- tenant hooks ----------------------------------------------------

    @abstractmethod
    def select_payload(self, params: ParamsT, ctx: SdkTaskContext) -> list[TargetT]: ...

    @abstractmethod
    def build_request(self, params: ParamsT, ctx: SdkTaskContext, item: TargetT) -> Any: ...

    @abstractmethod
    def push(self, params: ParamsT, ctx: SdkTaskContext, request: Any) -> Any: ...

    def on_response(
        self,
        params: ParamsT,
        ctx: SdkTaskContext,
        item: TargetT,
        response: Any,
    ) -> None:
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
        counts = SdkRowResult()
        items = self.select_payload(params, ctx)
        for item in items:
            try:
                req = self.build_request(params, ctx, item)
                resp = self.push(params, ctx, req)
                self.on_response(params, ctx, item, resp)
                counts.inc_success()
            except Exception as item_ex:
                counts.inc_failed()
                _log.warning("typed dispatch item failed: %s", item_ex)
        return self._result(params, counts, f"dispatched {counts.success}/{counts.total}")

    def _result(self, params: ParamsT, counts: SdkRowResult, default_message: str) -> SdkTaskResult:
        output = self.summarize(params, counts)
        if output is None:
            return SdkTaskResult.success_with(output=counts.to_output(), message=default_message)
        return SdkTaskResult.success_with(
            output=SdkTypedParameters.serialize(output), message=default_message
        )
