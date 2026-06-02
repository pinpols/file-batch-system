"""P0.5 surface tests: the public API exists and has the expected shape.

These tests intentionally do *not* exercise behavior (there isn't any
yet — bodies are ``...`` / ``NotImplementedError`` / stubs). They lock
in the *names*, *signatures*, and *value semantics* so Java engineers
diffing against the Java SDK see an exact mirror, and so downstream
lanes (P1-P5) can't accidentally rename a public symbol without a
visible test break.
"""

from __future__ import annotations

import inspect
from typing import get_type_hints

import pytest

import batch_worker_sdk
from batch_worker_sdk import (
    CancellationSignal,
    ProgressReporter,
    SdkTaskContext,
    SdkTaskHandler,
    SdkTaskResult,
    SdkTaskTypeDescriptor,
    WorkerRuntimeState,
)


def test_public_symbols_exported() -> None:
    """All 7 surface types are re-exported from the package root."""
    expected = {
        "CancellationSignal",
        "ProgressReporter",
        "SdkTaskContext",
        "SdkTaskHandler",
        "SdkTaskResult",
        "SdkTaskTypeDescriptor",
        "WorkerRuntimeState",
    }
    assert expected.issubset(set(batch_worker_sdk.__all__))
    for name in expected:
        assert hasattr(batch_worker_sdk, name), f"missing export: {name}"


def test_worker_runtime_state_has_four_values() -> None:
    """4 states aligned with Java ``WorkerRuntimeState``."""
    assert {s.value for s in WorkerRuntimeState} == {
        "NORMAL",
        "DEGRADED",
        "PAUSED",
        "DRAINING",
    }
    assert WorkerRuntimeState.NORMAL.accepts_new_tasks() is True
    assert WorkerRuntimeState.DEGRADED.accepts_new_tasks() is True
    assert WorkerRuntimeState.PAUSED.accepts_new_tasks() is False
    assert WorkerRuntimeState.DRAINING.accepts_new_tasks() is False


def test_sdk_task_handler_protocol_signature() -> None:
    """Protocol declares the 4 methods with the right names + arity."""
    members = dict(inspect.getmembers(SdkTaskHandler, predicate=inspect.isfunction))
    for method in ("task_type", "execute", "descriptor", "cancel"):
        assert method in members, f"SdkTaskHandler missing {method}"
    # execute must be a coroutine function
    assert inspect.iscoroutinefunction(members["execute"])
    # Param names match the design.
    exec_sig = inspect.signature(members["execute"])
    assert list(exec_sig.parameters) == ["self", "ctx"]


def test_sdk_task_context_defaults_and_immutability() -> None:
    """Construction with required fields + immutability + default attempt_no."""
    ctx = SdkTaskContext(
        tenant_id="t1",
        task_id=42,
        worker_code="worker-a",
        task_type="my_import",
    )
    assert ctx.tenant_id == "t1"
    assert ctx.task_id == 42
    assert ctx.attempt_no == 1
    assert ctx.parameters == {}
    assert ctx.runtime_attributes == {}
    # Frozen model: mutation should raise.
    with pytest.raises((TypeError, ValueError)):
        ctx.tenant_id = "t2"  # type: ignore[misc]


def test_sdk_task_context_is_dry_run() -> None:
    """``is_dry_run`` reads runtime_attributes first, then parameters."""
    base = {
        "tenant_id": "t",
        "task_id": 1,
        "worker_code": "w",
        "task_type": "tt",
    }
    assert SdkTaskContext(**base).is_dry_run() is False
    assert SdkTaskContext(**base, runtime_attributes={"dryRun": True}).is_dry_run() is True
    assert SdkTaskContext(**base, parameters={"dryRun": True}).is_dry_run() is True
    # runtime_attributes wins over parameters when both present.
    ctx = SdkTaskContext(
        **base,
        runtime_attributes={"dryRun": False},
        parameters={"dryRun": True},
    )
    assert ctx.is_dry_run() is False
    # String "true" honored (platform may inject as string).
    assert SdkTaskContext(**base, runtime_attributes={"dryRun": "true"}).is_dry_run() is True


def test_sdk_task_result_factories() -> None:
    """``success_with`` / ``fail`` produce the expected field shapes."""
    ok = SdkTaskResult.success_with(output={"rows": 10}, message="done")
    assert ok.success is True
    assert ok.output == {"rows": 10}
    assert ok.message == "done"

    ok_default = SdkTaskResult.success_with()
    assert ok_default.success is True
    assert ok_default.message == "ok"
    assert ok_default.output == {}

    err = SdkTaskResult.fail("ATOMIC_TIMEOUT", "task ran past deadline")
    assert err.success is False
    assert err.output["errorCode"] == "ATOMIC_TIMEOUT"
    assert err.message == "task ran past deadline"

    err_with_cause = SdkTaskResult.fail("ATOMIC_TIMEOUT", "boom", cause=TimeoutError("x"))
    assert err_with_cause.output["errorClass"] == "TimeoutError"


def test_cancellation_signal_default_and_mark() -> None:
    """``mark_cancelled`` flips the bit; default state is False."""
    sig = CancellationSignal()
    assert sig.is_cancellation_requested is False
    sig.mark_cancelled()
    assert sig.is_cancellation_requested is True


def test_progress_reporter_report_then_latest() -> None:
    """Lane U / P4: report stores a copy; latest returns an independent copy."""
    reporter = ProgressReporter()
    assert reporter.latest() is None
    reporter.report({"processed": 1})
    assert reporter.latest() == {"processed": 1}


def test_task_type_descriptor_fields() -> None:
    """Descriptor model accepts all 6 spec fields and is frozen."""
    desc = SdkTaskTypeDescriptor(
        task_type="my_import",
        display_name="My Import",
        input_schema={"type": "object"},
        parameters={"batchSize": 1000},
        outputs={"rows": "int"},
        required_env=["DB_PASSWORD"],
    )
    assert desc.task_type == "my_import"
    assert desc.required_env == ["DB_PASSWORD"]
    assert desc.input_schema == {"type": "object"}
    # Wire-alias path: payload using camel-case ``schema`` key works too.
    desc_aliased = SdkTaskTypeDescriptor.model_validate(
        {"task_type": "x", "schema": {"type": "object"}}
    )
    assert desc_aliased.input_schema == {"type": "object"}
    # Field type-hints exist (mypy-strict surrogate).
    hints = get_type_hints(SdkTaskTypeDescriptor)
    assert "task_type" in hints
    assert "required_env" in hints
