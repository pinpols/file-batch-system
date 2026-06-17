"""Lane A #2 — TaskDispatcher schemaVersion 协议守护。

对齐 Java ``TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS = Set.of("v1", "v2")``:
Python SDK 必须同时接受 ``v1`` 与 ``v2`` 信封,拒绝 ``v3`` 及未知。
"""

from __future__ import annotations

import asyncio
from typing import Any

import pytest
from pytest_httpx import HTTPXMock

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher
from batch_worker_sdk.dispatcher.dispatcher import DispatchDisposition
from batch_worker_sdk.internal._http import PlatformHttpClient


def _cfg() -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url="http://orch:8081",
        tenant_id="acme",
        worker_code="w-1",
        max_concurrent_tasks=4,
    )


def _msg(task_id: int, schema: str) -> dict[str, Any]:
    return {
        "schemaVersion": schema,
        "tenantId": "acme",
        "taskId": task_id,
        "jobCode": "daily",
        "workerType": "echo",
        "idempotencyKey": f"key-{task_id}",
    }


async def _drain(dispatcher: TaskDispatcher) -> None:
    await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)


@pytest.mark.parametrize("schema", ["v1", "v1.0", "v2", "v2.3"])
async def test_supported_schema_versions_accepted(schema: str, httpx_mock: HTTPXMock) -> None:
    cfg = _cfg()
    httpx_mock.add_response(url=cfg.base_url + "/internal/tasks/1/claim", status_code=200, json={})
    httpx_mock.add_response(url=cfg.base_url + "/internal/tasks/1/report", status_code=200, json={})
    http = PlatformHttpClient(cfg)
    dispatcher = TaskDispatcher(cfg, http)
    try:
        await dispatcher.on_message(_msg(1, schema))
        # 进入 in-flight 即说明 schema 通过门控;等待执行完成。
        assert dispatcher.in_flight_count() == 1
        await _drain(dispatcher)
    finally:
        await http.close()


@pytest.mark.parametrize("schema", ["v3", "v0", "foo"])
async def test_unsupported_schema_versions_dropped(
    schema: str, caplog: pytest.LogCaptureFixture, httpx_mock: HTTPXMock
) -> None:

    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    dispatcher = TaskDispatcher(cfg, http)
    try:
        caplog.set_level("WARNING", logger="batch_worker_sdk.dispatcher.dispatcher")
        # 未知大版本:不进 in-flight、不发 HTTP,且 offset 不提交(RETRY_LATER)——§A 契约。
        disposition = await dispatcher.on_message(_msg(99, schema))
        assert disposition is DispatchDisposition.RETRY_LATER
        assert dispatcher.in_flight_count() == 0
        assert httpx_mock.get_requests() == []
        assert any("unsupported schemaVersion" in r.message for r in caplog.records)
    finally:
        await http.close()


async def test_missing_or_blank_schema_accepted_as_v1(httpx_mock: HTTPXMock) -> None:
    """缺字段 / 空白 schemaVersion 按 v1 解析并 accept(对齐 Java + 契约 fixture 16)。"""

    cfg = _cfg()
    # taskId 7 = 缺 schemaVersion 字段;taskId 8 = 空白字符串。两者都应 accept → CLAIM。
    for tid in (7, 8):
        httpx_mock.add_response(
            url=cfg.base_url + f"/internal/tasks/{tid}/claim", status_code=200, json={}
        )
        httpx_mock.add_response(
            url=cfg.base_url + f"/internal/tasks/{tid}/report", status_code=200, json={}
        )
    http = PlatformHttpClient(cfg)
    dispatcher = TaskDispatcher(cfg, http)
    try:
        missing = _msg(7, "")
        del missing["schemaVersion"]  # 字段整体缺失
        assert await dispatcher.on_message(missing) is DispatchDisposition.ACCEPTED
        assert await dispatcher.on_message(_msg(8, "  ")) is DispatchDisposition.ACCEPTED
        assert dispatcher.in_flight_count() == 2
        await _drain(dispatcher)
    finally:
        await http.close()
