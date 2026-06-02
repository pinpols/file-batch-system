"""Unit tests for the ``@batch_task`` decorator."""

from __future__ import annotations

import pytest

from batch_worker_sdk import (
    SdkTaskContext,
    SdkTaskHandler,
    SdkTaskResult,
    SdkTaskTypeDescriptor,
    batch_task,
    collect_registered_handlers,
)
from batch_worker_sdk.decorator import _clear_registered_handlers
from batch_worker_sdk.testkit import make_test_context


@pytest.fixture(autouse=True)
def _reset_registry():
    """Each test starts with an empty registry — avoid cross-test bleed."""
    _clear_registered_handlers()
    yield
    _clear_registered_handlers()


def test_decorator_registers_handler() -> None:
    @batch_task("job-a")
    async def handler_a(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({"id": ctx.task_id})

    handlers = collect_registered_handlers()
    assert len(handlers) == 1
    assert handlers[0].task_type() == "job-a"
    # The returned object satisfies the structural Protocol.
    assert isinstance(handlers[0], SdkTaskHandler)


async def test_decorator_execute_passes_through() -> None:
    @batch_task("job-b")
    async def handler_b(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({"echoed": ctx.parameters})

    [reg] = collect_registered_handlers()
    result = await reg.execute(make_test_context(parameters={"x": 1}))
    assert result.success is True
    assert result.output == {"echoed": {"x": 1}}


def test_multiple_handlers_each_registered() -> None:
    @batch_task("j1")
    async def h1(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    @batch_task("j2")
    async def h2(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    handlers = collect_registered_handlers()
    assert {h.task_type() for h in handlers} == {"j1", "j2"}


def test_collect_returns_snapshot_copy() -> None:
    @batch_task("snap")
    async def h(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    a = collect_registered_handlers()
    a.clear()
    b = collect_registered_handlers()
    assert len(b) == 1  # internal registry untouched


def test_empty_task_type_rejected() -> None:
    with pytest.raises(ValueError, match="non-empty"):

        @batch_task("")
        async def h(ctx: SdkTaskContext) -> SdkTaskResult:
            return SdkTaskResult.success_with({})


def test_sync_function_rejected() -> None:
    with pytest.raises(TypeError, match="async def"):

        @batch_task("sync-bad")
        def sync_h(ctx: SdkTaskContext) -> SdkTaskResult:  # type: ignore[misc]
            return SdkTaskResult.success_with({})


def test_descriptor_mismatch_rejected() -> None:
    desc = SdkTaskTypeDescriptor(task_type="other")
    with pytest.raises(ValueError, match=r"descriptor\.task_type"):

        @batch_task("job-z", descriptor=desc)
        async def h(ctx: SdkTaskContext) -> SdkTaskResult:
            return SdkTaskResult.success_with({})


def test_descriptor_passthrough() -> None:
    desc = SdkTaskTypeDescriptor(task_type="job-d", display_name="D")

    @batch_task("job-d", descriptor=desc)
    async def h(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    [reg] = collect_registered_handlers()
    assert reg.descriptor() is desc


def test_handler_repr_is_informative() -> None:
    @batch_task("job-repr")
    async def reprhandler(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    [reg] = collect_registered_handlers()
    text = repr(reg)
    assert "job-repr" in text
    assert "reprhandler" in text
