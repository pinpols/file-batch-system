"""TaskDispatcher 单元测试(P2)。"""

from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest
from pytest_httpx import HTTPXMock

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher, WorkerRuntimeState
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult


def _cfg(tenant: str = "acme") -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url="http://orch:8081",
        tenant_id=tenant,
        worker_code="w-1",
        max_concurrent_tasks=4,
    )


def _msg(task_id: int = 1, tenant: str = "acme") -> dict[str, Any]:
    return {
        "schemaVersion": "v2",
        "tenantId": tenant,
        "taskId": task_id,
        "jobCode": "daily",
        "workerType": "echo",
        "idempotencyKey": f"key-{task_id}",
    }


async def _make() -> tuple[TaskDispatcher, PlatformHttpClient]:
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    return TaskDispatcher(cfg, http), http


async def test_tenant_mismatch_drops_message(
    caplog: pytest.LogCaptureFixture, httpx_mock: HTTPXMock
) -> None:
    """§J1:外租户 → ERROR 日志 + 丢弃,不发 HTTP。"""
    dispatcher, http = await _make()
    try:
        caplog.set_level("ERROR", logger="batch_worker_sdk.dispatcher.dispatcher")
        await dispatcher.on_message(_msg(task_id=42, tenant="other-tenant"))
        assert dispatcher.in_flight_count() == 0
        assert any("tenant_mismatch_drop" in r.message for r in caplog.records)
        # 没有发出任何 HTTP 请求。
        assert httpx_mock.get_requests() == []
    finally:
        await http.close()


async def test_fatal_silently_drops_subsequent_messages(httpx_mock: HTTPXMock) -> None:
    """AuthError 触发 fatal 后,后续 on_message 全部 no-op。"""
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    httpx_mock.add_response(url=cfg.base_url + "/internal/tasks/1/claim", status_code=401)
    dispatcher = TaskDispatcher(cfg, http)
    try:
        await dispatcher.on_message(_msg(task_id=1))
        # 直接 await 把派生出的 CLAIM 任务排干。
        await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)
        assert dispatcher.is_fatal is True
        # 新消息应被丢弃,不再发 HTTP。
        before = len(httpx_mock.get_requests())
        await dispatcher.on_message(_msg(task_id=2))
        assert dispatcher.in_flight_count() == 0
        assert len(httpx_mock.get_requests()) == before
    finally:
        await http.close()


async def test_in_flight_count_tracks_dispatches(httpx_mock: HTTPXMock) -> None:
    """in_flight_count 反映活跃的 asyncio 任务数。"""
    cfg = _cfg()
    # 阻塞 claim,让任务保持 in-flight。
    blocker = asyncio.Event()

    async def slow_claim(*_a: Any, **_kw: Any) -> tuple[dict[str, Any], int]:
        await blocker.wait()
        return {}, 200

    async def fake_report(*_a: Any, **_kw: Any) -> dict[str, Any]:
        return {}

    http = PlatformHttpClient(cfg)
    http.claim_status = slow_claim  # type: ignore[method-assign]
    http.report = fake_report  # type: ignore[method-assign]
    dispatcher = TaskDispatcher(cfg, http)
    try:
        await dispatcher.on_message(_msg(task_id=100))
        await asyncio.sleep(0)  # 让 task 起来
        assert dispatcher.in_flight_count() == 1
        assert 100 in dispatcher.in_flight_task_ids()
        blocker.set()
        await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)
        assert dispatcher.in_flight_task_ids() == set()
    finally:
        await http.close()


async def test_shutdown_timeout_returns_when_drained(httpx_mock: HTTPXMock) -> None:
    """任务完成够快时 shutdown() 在 timeout 内排干。"""
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    httpx_mock.add_response(url=cfg.base_url + "/internal/tasks/1/claim", status_code=200, json={})
    httpx_mock.add_response(url=cfg.base_url + "/internal/tasks/1/report", status_code=200, json={})
    dispatcher = TaskDispatcher(cfg, http)
    try:
        await dispatcher.on_message(_msg(task_id=1))
        await dispatcher.shutdown(timeout=2.0)
        assert dispatcher.is_draining is True
        assert dispatcher.in_flight_count() == 0
    finally:
        await http.close()


async def test_apply_platform_directive_sets_state(httpx_mock: HTTPXMock) -> None:
    """Directive runtimeState=DRAINING → dispatcher 拒收新任务。"""
    dispatcher, http = await _make()
    try:
        dispatcher.apply_platform_directive({"runtimeState": "DRAINING"})
        assert dispatcher.runtime_state == WorkerRuntimeState.DRAINING
        assert dispatcher.accepts_new_tasks() is False
        await dispatcher.on_message(_msg(task_id=7))
        assert dispatcher.in_flight_count() == 0
    finally:
        await http.close()


class _RecordingHandler:
    """记录 execute 调用 + 返回可配置 SdkTaskResult 的测试 handler。"""

    def __init__(self, worker_type: str, result: SdkTaskResult | Exception) -> None:
        self._worker_type = worker_type
        self._result = result
        self.calls: list[SdkTaskContext] = []

    def task_type(self) -> str:
        return self._worker_type

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        self.calls.append(ctx)
        if isinstance(self._result, Exception):
            raise self._result
        return self._result

    def descriptor(self) -> None:
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        return None


async def _run_with_handler(
    handler: _RecordingHandler, msg: dict[str, Any], httpx_mock: HTTPXMock
) -> list[Any]:
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    httpx_mock.add_response(
        url=cfg.base_url + f"/internal/tasks/{msg['taskId']}/claim", status_code=200, json={}
    )
    httpx_mock.add_response(
        url=cfg.base_url + f"/internal/tasks/{msg['taskId']}/report", status_code=200, json={}
    )
    dispatcher = TaskDispatcher(cfg, http, handlers={handler.task_type(): handler})
    try:
        await dispatcher.on_message(msg)
        await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)
    finally:
        await http.close()
    return [r for r in httpx_mock.get_requests() if r.url.path.endswith("/report")]


def _report_body(report_reqs: list[Any]) -> dict[str, Any]:
    assert len(report_reqs) == 1, f"expected exactly one /report, got {len(report_reqs)}"
    return json.loads(report_reqs[0].content.decode("utf-8"))


async def test_handler_success_is_executed_and_reported(httpx_mock: HTTPXMock) -> None:
    """P0:handler.execute 被真正调用,success=True 透传到 REPORT。"""
    handler = _RecordingHandler(
        "echo", SdkTaskResult.success_with(output={"rows": 3}, message="done")
    )
    report_reqs = await _run_with_handler(handler, _msg(task_id=1), httpx_mock)
    assert len(handler.calls) == 1
    assert handler.calls[0].task_id == 1
    body = _report_body(report_reqs)
    assert body["success"] is True
    assert body["outputs"] == {"rows": 3}
    assert body["resultSummary"] == "done"


async def test_handler_failure_result_reports_failure(httpx_mock: HTTPXMock) -> None:
    """handler 返回 success=False → REPORT success=false + errorCode 透传。"""
    handler = _RecordingHandler("echo", SdkTaskResult.fail("MY_ERR", "boom"))
    report_reqs = await _run_with_handler(handler, _msg(task_id=2), httpx_mock)
    assert len(handler.calls) == 1
    body = _report_body(report_reqs)
    assert body["success"] is False
    assert body["errorCode"] == "MY_ERR"
    assert body["resultSummary"] == "boom"


async def test_handler_exception_reports_failure_not_leaks(httpx_mock: HTTPXMock) -> None:
    """handler 抛异常 → REPORT failure(异常不从后台 task 泄漏)。"""
    handler = _RecordingHandler("echo", RuntimeError("kaboom"))
    report_reqs = await _run_with_handler(handler, _msg(task_id=3), httpx_mock)
    assert len(handler.calls) == 1
    body = _report_body(report_reqs)
    assert body["success"] is False
    assert "kaboom" in body["resultSummary"]


async def test_claim_409_skips_handler_and_report(httpx_mock: HTTPXMock) -> None:
    """fixture 08:CLAIM 409 → 不执行 handler,不调用 /report。"""
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    httpx_mock.add_response(
        url=cfg.base_url + "/internal/tasks/4/claim",
        status_code=409,
        json={"code": "ALREADY_CLAIMED", "message": "claimed by w-2"},
    )
    handler = _RecordingHandler("echo", SdkTaskResult.success_with())
    dispatcher = TaskDispatcher(cfg, http, handlers={"echo": handler})
    try:
        await dispatcher.on_message(_msg(task_id=4))
        await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)
    finally:
        await http.close()
    assert handler.calls == []
    report_reqs = [r for r in httpx_mock.get_requests() if r.url.path.endswith("/report")]
    assert report_reqs == []


async def test_partition_invocation_id_cached_at_claim(httpx_mock: HTTPXMock) -> None:
    """CLAIM 时缓存 partitionInvocationId,供 lease renew 回读。"""
    cfg = _cfg()
    blocker = asyncio.Event()

    async def slow_claim(*_a: Any, **_kw: Any) -> tuple[dict[str, Any], int]:
        await blocker.wait()
        return {}, 200

    async def fake_report(*_a: Any, **_kw: Any) -> dict[str, Any]:
        return {}

    http = PlatformHttpClient(cfg)
    http.claim_status = slow_claim  # type: ignore[method-assign]
    http.report = fake_report  # type: ignore[method-assign]
    handler = _RecordingHandler("echo", SdkTaskResult.success_with())
    dispatcher = TaskDispatcher(cfg, http, handlers={"echo": handler})
    try:
        msg = _msg(task_id=5)
        msg["runtimeAttributes"] = {"partitionInvocationId": "inv-9"}
        await dispatcher.on_message(msg)
        await asyncio.sleep(0)
        assert dispatcher.partition_invocation_id(5) == "inv-9"
        blocker.set()
        await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)
    finally:
        await http.close()


async def test_unsupported_schema_version_drops(
    caplog: pytest.LogCaptureFixture, httpx_mock: HTTPXMock
) -> None:
    """schemaVersion=v3 → 拒收(向前兼容防御)。"""
    dispatcher, http = await _make()
    try:
        caplog.set_level("WARNING", logger="batch_worker_sdk.dispatcher.dispatcher")
        msg = _msg(task_id=5)
        msg["schemaVersion"] = "v3"
        await dispatcher.on_message(msg)
        assert dispatcher.in_flight_count() == 0
        assert any("unsupported schemaVersion" in r.message for r in caplog.records)
    finally:
        await http.close()
