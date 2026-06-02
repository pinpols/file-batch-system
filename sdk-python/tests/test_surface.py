"""P0.5 表面测试:公开 API 存在且形状符合预期。

这些测试有意 *不* 触发行为(目前也没有行为可触发 —— 函数体都是
``...`` / ``NotImplementedError`` / 桩)。它们锁住 *名字*、*签名* 与
*值语义*,这样 Java 工程师对照 Java SDK diff 时能看到完全一致的镜像,
下游 lane(P1-P5)也无法在不引发可见测试失败的情况下重命名任何
公开符号。
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
    """7 个表面类型全部从包根 re-export。"""
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
    """4 个状态与 Java ``WorkerRuntimeState`` 对齐。"""
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
    """Protocol 声明了名字与参数数都正确的 4 个方法。"""
    members = dict(inspect.getmembers(SdkTaskHandler, predicate=inspect.isfunction))
    for method in ("task_type", "execute", "descriptor", "cancel"):
        assert method in members, f"SdkTaskHandler missing {method}"
    # execute 必须是协程函数
    assert inspect.iscoroutinefunction(members["execute"])
    # 参数名与设计一致。
    exec_sig = inspect.signature(members["execute"])
    assert list(exec_sig.parameters) == ["self", "ctx"]


def test_sdk_task_context_defaults_and_immutability() -> None:
    """构造时带必填字段、不可变,attempt_no 有默认值。"""
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
    # frozen 模型:赋值应抛异常。
    with pytest.raises((TypeError, ValueError)):
        ctx.tenant_id = "t2"  # type: ignore[misc]


def test_sdk_task_context_is_dry_run() -> None:
    """``is_dry_run`` 优先读 runtime_attributes,其次读 parameters。"""
    base = {
        "tenant_id": "t",
        "task_id": 1,
        "worker_code": "w",
        "task_type": "tt",
    }
    assert SdkTaskContext(**base).is_dry_run() is False
    assert SdkTaskContext(**base, runtime_attributes={"dryRun": True}).is_dry_run() is True
    assert SdkTaskContext(**base, parameters={"dryRun": True}).is_dry_run() is True
    # 两者同时存在时 runtime_attributes 胜过 parameters。
    ctx = SdkTaskContext(
        **base,
        runtime_attributes={"dryRun": False},
        parameters={"dryRun": True},
    )
    assert ctx.is_dry_run() is False
    # 字符串 "true" 也认(平台可能以字符串形式注入)。
    assert SdkTaskContext(**base, runtime_attributes={"dryRun": "true"}).is_dry_run() is True


def test_sdk_task_result_factories() -> None:
    """``success_with`` / ``fail`` 产出期望的字段形状。"""
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
    """``mark_cancelled`` 翻转标志;默认状态为 False。"""
    sig = CancellationSignal()
    assert sig.is_cancellation_requested is False
    sig.mark_cancelled()
    assert sig.is_cancellation_requested is True


def test_progress_reporter_report_then_latest() -> None:
    """P4:report 存入副本;latest 返回独立副本。"""
    reporter = ProgressReporter()
    assert reporter.latest() is None
    reporter.report({"processed": 1})
    assert reporter.latest() == {"processed": 1}


def test_task_type_descriptor_fields() -> None:
    """Descriptor 模型接受 spec 全部 6 个字段,且为 frozen。"""
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
    # wire alias 路径:payload 用 camelCase 的 ``schema`` 键也能 work。
    desc_aliased = SdkTaskTypeDescriptor.model_validate(
        {"task_type": "x", "schema": {"type": "object"}}
    )
    assert desc_aliased.input_schema == {"type": "object"}
    # 字段 type hint 存在(mypy-strict 替代品)。
    hints = get_type_hints(SdkTaskTypeDescriptor)
    assert "task_type" in hints
    assert "required_env" in hints
