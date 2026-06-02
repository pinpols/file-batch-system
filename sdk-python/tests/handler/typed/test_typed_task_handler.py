"""Tests for ``SdkTypedTaskHandler`` — single-method typed entry point."""

from __future__ import annotations

import pytest
from pydantic import BaseModel

from batch_worker_sdk.handler.typed import SdkTypedTaskHandler

from ._helpers import make_ctx


class _In(BaseModel):
    name: str
    n: int


class _Out(BaseModel):
    greeting: str


class _Greeter(SdkTypedTaskHandler[_In, _Out]):
    def task_type(self) -> str:
        return "greet"

    async def _do_typed_execute(self, ctx, params):  # type: ignore[override]
        return _Out(greeting=f"hello {params.name}*{params.n}")


@pytest.mark.asyncio
async def test_typed_task_handler_happy_path() -> None:
    h = _Greeter()
    r = await h.execute(make_ctx({"name": "alice", "n": 2}))
    assert r.success is True
    assert r.output == {"greeting": "hello alice*2"}
    assert r.message == "ok"


@pytest.mark.asyncio
async def test_typed_task_handler_invalid_params_returns_fail() -> None:
    h = _Greeter()
    r = await h.execute(make_ctx({"name": "alice"}))  # missing `n`
    assert r.success is False
    assert "invalid parameters for taskType=greet" in (r.message or "")
    assert r.output["errorCode"] == "INVALID_TYPED_PARAMS"


@pytest.mark.asyncio
async def test_typed_task_handler_none_output_serializes_empty_map() -> None:
    class _Maybe(SdkTypedTaskHandler[_In, _Out]):
        def task_type(self) -> str:
            return "maybe"

        async def _do_typed_execute(self, ctx, params):  # type: ignore[override]
            return None

    r = await _Maybe().execute(make_ctx({"name": "x", "n": 1}))
    assert r.success is True
    assert r.output == {}


def test_typed_task_handler_resolves_input_model_from_generics() -> None:
    assert _Greeter._input_model is _In
