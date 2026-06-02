"""Typed-parameter deserialization helper — Java parity:
``com.example.batch.sdk.handler.typed.SdkTypedParameters``.

Java uses Jackson + reflective ``JavaType.findTypeParameters`` to
resolve the generic ``<I>`` from the concrete handler subclass. Python
takes the same job — but with pydantic v2 doing both schema validation
and Map<->model conversion — driven by an explicit ``model_type``
argument resolved at handler-construction time from ``typing.Generic``
parameters (see :class:`SdkTypedTaskHandler`).

Why a separate helper instead of inlining ``model.model_validate``:
the 4 row-flow templates (Import/Export/Process/Dispatch) and the
single-method typed handler all share the exact same parse/serialize
semantics; Java composes them and Python mirrors that — so a tenant
sees identical error text everywhere ("invalid parameters for
taskType=…: <pydantic msg>") rather than ad-hoc per-template wording.
"""

from __future__ import annotations

from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError

T = TypeVar("T", bound=BaseModel)


class SdkTypedParameters:
    """Pydantic-backed mirror of Java ``SdkTypedParameters``.

    Stateless utility (Java keeps a per-instance ``ObjectMapper`` +
    resolved ``JavaType``; Python doesn't need that — pydantic's
    ``model_validate``/``model_dump`` does both jobs without
    pre-resolution).
    """

    @staticmethod
    def parse(raw: dict[str, Any] | None, model_type: type[T]) -> T:
        """Deserialize ``raw`` (typically ``ctx.parameters``) into ``model_type``.

        Raises ``ValueError`` with a flat, human-readable message on
        validation failure — typed templates catch this and convert to
        :meth:`SdkTaskResult.fail` (no business code runs on bad input,
        matching Java's ``IllegalArgumentException`` contract).
        """
        if not issubclass(model_type, BaseModel):
            raise TypeError(
                f"SdkTypedParameters.parse requires a pydantic BaseModel subclass, "
                f"got {model_type!r}"
            )
        payload: dict[str, Any] = raw or {}
        try:
            return model_type.model_validate(payload)
        except ValidationError as e:
            # Compact one-liner so it lands cleanly in REPORT message.
            errs = "; ".join(
                f"{'.'.join(str(p) for p in err['loc']) or '<root>'}: {err['msg']}"
                for err in e.errors()
            )
            raise ValueError(f"parameters do not match {model_type.__name__}: {errs}") from e

    @staticmethod
    def serialize(model: BaseModel | None) -> dict[str, Any]:
        """Serialize a pydantic model into the ``output`` map.

        ``None`` -> empty dict (mirrors Java ``toOutputMap(null) -> Map.of()``).
        """
        if model is None:
            return {}
        if not isinstance(model, BaseModel):
            raise TypeError(
                f"SdkTypedParameters.serialize expects a pydantic BaseModel, "
                f"got {type(model).__name__}"
            )
        return model.model_dump(mode="json")
