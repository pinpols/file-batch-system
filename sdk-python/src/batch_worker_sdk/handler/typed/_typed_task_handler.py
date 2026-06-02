"""Single-method typed task handler — Java parity:
``com.example.batch.sdk.handler.typed.SdkTypedTaskHandler``.

Java: ``abstract class SdkTypedTaskHandler<I, O>`` — tenant returns a
business object ``O``, framework serializes it into the output map.
Python: ``Generic[InputT, OutputT]`` with pydantic models on both
ends. ``OutputT`` may be ``None`` (tenant returns ``None`` -> empty
output, matching Java's ``null -> Map.of()``).
"""

from __future__ import annotations

from typing import get_args, get_origin

from pydantic import BaseModel

from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.handler.typed._typed_parameters import SdkTypedParameters
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult


def _resolve_input_model(cls: type, generic_base: type, index: int) -> type[BaseModel]:
    """Walk ``__orig_bases__`` to find the concrete ``InputT`` bound on ``generic_base``.

    Mirrors Java's ``TypeFactory.findTypeParameters(self, declaringBase)``.
    Falls back to raising — Python typed handlers MUST close ``InputT``
    on a pydantic model (no "raw Object" fallback like Java has, because
    pydantic needs a concrete class to validate against).
    """
    for klass in cls.__mro__:
        for base in getattr(klass, "__orig_bases__", ()):
            origin = get_origin(base)
            if origin is generic_base or (origin is None and base is generic_base):
                args = get_args(base)
                if args and len(args) > index and isinstance(args[index], type):
                    candidate = args[index]
                    if issubclass(candidate, BaseModel):
                        return candidate  # type: ignore[no-any-return]
    raise TypeError(
        f"{cls.__name__} must close the first generic parameter of "
        f"{generic_base.__name__} on a pydantic BaseModel subclass; "
        f"got {cls.__mro__[1:3]}"
    )


class SdkTypedTaskHandler[InputT: BaseModel, OutputT: BaseModel]:
    """Generic typed handler base — strongly-typed in/out via pydantic.

    Subclasses MUST close ``InputT`` on a concrete ``BaseModel``
    subclass at the class declaration site so the framework can
    deserialize ``ctx.parameters`` without reflection guesswork.

    Implements :class:`SdkTaskHandler` Protocol structurally (no
    ``isinstance`` inheritance needed thanks to ``@runtime_checkable``).
    """

    # Subclasses can override at class level instead of via constructor.
    _input_model: type[BaseModel] | None = None

    def __init_subclass__(cls, **kwargs: object) -> None:
        super().__init_subclass__(**kwargs)
        # Skip intermediate abstract bases (e.g. SdkAbstractTypedImportHandler);
        # only resolve once a subclass closes the generics on concrete models.
        if cls._input_model is not None:
            return
        try:
            cls._input_model = _resolve_input_model(cls, SdkTypedTaskHandler, 0)
        except TypeError:
            # Subclass is still abstract — defer; concrete child will resolve.
            cls._input_model = None

    def task_type(self) -> str:
        raise NotImplementedError

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        return None

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        model = self._input_model
        if model is None:
            return SdkTaskResult.fail(
                "TYPED_PARAMS_UNRESOLVED",
                f"{type(self).__name__}: input pydantic model could not be resolved "
                f"from generic parameters; close InputT on a concrete BaseModel.",
            )
        try:
            params = SdkTypedParameters.parse(ctx.parameters, model)
        except ValueError as ex:
            return SdkTaskResult.fail(
                "INVALID_TYPED_PARAMS",
                f"invalid parameters for taskType={self.task_type()}: {ex}",
                cause=ex,
            )
        result = await self._do_typed_execute(ctx, params)  # type: ignore[arg-type]
        output_map = SdkTypedParameters.serialize(result)
        return SdkTaskResult.success_with(output=output_map, message=self._success_message(result))

    async def _do_typed_execute(self, ctx: SdkTaskContext, params: InputT) -> OutputT | None:
        """Tenant override — strongly-typed business logic."""
        raise NotImplementedError

    def _success_message(self, output: OutputT | None) -> str:
        return "ok"


# Sanity check at import — Protocol parity. Done at runtime here (not at
# module top with `assert isinstance(..., SdkTaskHandler)`) because the
# class itself isn't instantiable; we just confirm the methods exist.
assert hasattr(SdkTypedTaskHandler, "task_type")
assert hasattr(SdkTypedTaskHandler, "execute")
# Protocol structural check happens at instance time; class doesn't need it.
_ = SdkTaskHandler  # re-export hint
