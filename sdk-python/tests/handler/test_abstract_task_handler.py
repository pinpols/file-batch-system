"""Unit tests for SdkAbstractTaskHandler template-method behavior."""

from __future__ import annotations

from batch_worker_sdk import (
    SdkAbstractTaskHandler,
    SdkTaskContext,
    SdkTaskHandler,
    SdkTaskResult,
)
from batch_worker_sdk.handler._base import (
    CANCELLED_CODE,
    HANDLER_ERROR_CODE,
    NULL_RESULT_CODE,
)
from batch_worker_sdk.task.cancellation import CancellationSignal
from batch_worker_sdk.testkit import make_test_context


class _MinimalHandler(SdkAbstractTaskHandler):
    """Records hook calls; succeeds in _do_execute."""

    def __init__(self) -> None:
        self.calls: list[str] = []

    def task_type(self) -> str:
        return "minimal"

    async def _validate(self, ctx: SdkTaskContext) -> None:
        self.calls.append("validate")

    async def _before(self, ctx: SdkTaskContext) -> None:
        self.calls.append("before")

    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        self.calls.append("do_execute")
        return SdkTaskResult.success_with({"ok": True}, "done")

    async def _after(self, ctx: SdkTaskContext, result: SdkTaskResult) -> None:
        self.calls.append("after")

    async def _cleanup(self, ctx: SdkTaskContext) -> None:
        self.calls.append("cleanup")


class _RaisingHandler(SdkAbstractTaskHandler):
    def task_type(self) -> str:
        return "boom"

    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        raise RuntimeError("kaboom")


class _NullResultHandler(SdkAbstractTaskHandler):
    def task_type(self) -> str:
        return "null"

    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        return None  # type: ignore[return-value]


class _ValidateFailHandler(SdkAbstractTaskHandler):
    def __init__(self) -> None:
        self.cleanup_called = False

    def task_type(self) -> str:
        return "vfail"

    async def _validate(self, ctx: SdkTaskContext) -> None:
        raise ValueError("bad input")

    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({}, "should not reach")

    async def _cleanup(self, ctx: SdkTaskContext) -> None:
        self.cleanup_called = True


async def test_template_method_order_on_success() -> None:
    h = _MinimalHandler()
    result = await h.execute(make_test_context())
    assert result.success is True
    assert result.message == "done"
    assert h.calls == ["validate", "before", "do_execute", "after", "cleanup"]


async def test_do_execute_exception_becomes_fail_result() -> None:
    result = await _RaisingHandler().execute(make_test_context())
    assert result.success is False
    assert result.output["errorCode"] == HANDLER_ERROR_CODE
    assert result.output["errorClass"] == "RuntimeError"
    assert "kaboom" in (result.message or "")


async def test_null_result_converted_to_fail() -> None:
    result = await _NullResultHandler().execute(make_test_context())
    assert result.success is False
    assert result.output["errorCode"] == NULL_RESULT_CODE


async def test_cleanup_skipped_when_before_not_reached() -> None:
    """If _validate raises (before _before runs), _cleanup must not be called."""
    h = _ValidateFailHandler()
    result = await h.execute(make_test_context())
    assert result.success is False
    assert h.cleanup_called is False


async def test_cancel_signal_short_circuits_before_do_execute() -> None:
    signal = CancellationSignal()
    signal.mark_cancelled()
    ctx = make_test_context(cancel_signal=signal)
    result = await _MinimalHandler().execute(ctx)
    assert result.success is False
    assert result.output["errorCode"] == CANCELLED_CODE


def test_base_satisfies_protocol() -> None:
    """Structural Protocol check — base instances pass isinstance()."""
    assert isinstance(_MinimalHandler(), SdkTaskHandler)


def test_descriptor_default_is_none() -> None:
    assert _MinimalHandler().descriptor() is None
