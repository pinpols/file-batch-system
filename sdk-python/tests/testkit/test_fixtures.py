"""testkit fixture helper 的单元测试。"""

from __future__ import annotations

from datetime import timedelta

from batch_worker_sdk import SdkTaskResult
from batch_worker_sdk.testkit import (
    RecordingHandler,
    make_test_config,
    make_test_context,
)


def test_make_test_context_defaults() -> None:
    ctx = make_test_context()
    assert ctx.task_id == 1
    assert ctx.tenant_id == "test-tenant"
    assert ctx.worker_code == "test-worker"
    assert ctx.task_type == "test-task"
    assert ctx.parameters == {}
    assert ctx.attempt_no == 1


def test_make_test_context_overrides() -> None:
    ctx = make_test_context(
        task_id=42,
        parameters={"k": "v"},
        biz_date="2026-06-02",
        attempt_no=3,
        runtime_attributes={"dryRun": True},
    )
    assert ctx.task_id == 42
    assert ctx.parameters == {"k": "v"}
    assert ctx.biz_date == "2026-06-02"
    assert ctx.attempt_no == 3
    assert ctx.is_dry_run() is True


def test_make_test_config_defaults_pass_validation() -> None:
    cfg = make_test_config()
    assert cfg.base_url == "http://localhost:8081"
    assert cfg.tenant_id == "test-tenant"
    assert cfg.heartbeat_interval == timedelta(seconds=5)


def test_make_test_config_overrides() -> None:
    cfg = make_test_config(base_url="http://orch:9000", api_key="secret")
    assert cfg.base_url == "http://orch:9000"
    assert cfg.api_key == "secret"


async def test_recording_handler_records_execute_calls() -> None:
    rec = RecordingHandler(task_type_value="my-job")
    assert rec.task_type() == "my-job"
    ctx1 = make_test_context(task_id=1)
    ctx2 = make_test_context(task_id=2)
    await rec.execute(ctx1)
    await rec.execute(ctx2)
    assert len(rec.calls) == 2
    assert [c.task_id for c in rec.calls] == [1, 2]


async def test_recording_handler_custom_result() -> None:
    rec = RecordingHandler(
        result=SdkTaskResult.fail("BOOM", "kaboom"),
    )
    result = await rec.execute(make_test_context())
    assert result.success is False
    assert result.output["errorCode"] == "BOOM"


def test_recording_handler_cancel_recorded() -> None:
    rec = RecordingHandler()
    ctx = make_test_context()
    rec.cancel(ctx)
    assert rec.cancel_calls == [ctx]


def test_recording_handler_descriptor_passthrough() -> None:
    rec = RecordingHandler()
    assert rec.descriptor() is None
