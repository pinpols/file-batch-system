"""Unit tests for SdkAbstractAtomicHandler shape."""

from __future__ import annotations

from batch_worker_sdk import SdkAbstractAtomicHandler, SdkTaskContext
from batch_worker_sdk.handler._base import HANDLER_ERROR_CODE
from batch_worker_sdk.testkit import make_test_context


class _OkAtomic(SdkAbstractAtomicHandler[int]):
    def task_type(self) -> str:
        return "atomic-ok"

    async def _do_invoke(self, ctx: SdkTaskContext) -> int:
        return 42


class _NoneAtomic(SdkAbstractAtomicHandler[int]):
    def task_type(self) -> str:
        return "atomic-none"

    async def _do_invoke(self, ctx: SdkTaskContext) -> int | None:
        return None


class _RaisingAtomic(SdkAbstractAtomicHandler[int]):
    def task_type(self) -> str:
        return "atomic-bad"

    async def _do_invoke(self, ctx: SdkTaskContext) -> int:
        raise ValueError("nope")


async def test_atomic_success_wraps_result() -> None:
    r = await _OkAtomic().execute(make_test_context())
    assert r.success is True
    assert r.output == {"result": 42}
    assert r.message == "invoked"


async def test_atomic_none_produces_empty_output_map() -> None:
    r = await _NoneAtomic().execute(make_test_context())
    assert r.success is True
    assert r.output == {}


async def test_atomic_exception_becomes_fail() -> None:
    r = await _RaisingAtomic().execute(make_test_context())
    assert r.success is False
    assert r.output["errorCode"] == HANDLER_ERROR_CODE
    assert "nope" in (r.message or "")
