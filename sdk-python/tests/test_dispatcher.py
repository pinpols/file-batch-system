"""Unit tests for TaskDispatcher — Lane S (P2)."""

from __future__ import annotations

import asyncio
from typing import Any

import pytest
from pytest_httpx import HTTPXMock

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher, WorkerRuntimeState
from batch_worker_sdk._http import PlatformHttpClient


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
    """Lane J §J1: foreign tenant → ERROR log + drop, no HTTP call."""
    dispatcher, http = await _make()
    try:
        caplog.set_level("ERROR", logger="batch_worker_sdk.dispatcher")
        await dispatcher.on_message(_msg(task_id=42, tenant="other-tenant"))
        assert dispatcher.in_flight_count() == 0
        assert any("tenant_mismatch_drop" in r.message for r in caplog.records)
        # No HTTP requests issued.
        assert httpx_mock.get_requests() == []
    finally:
        await http.close()


async def test_fatal_silently_drops_subsequent_messages(httpx_mock: HTTPXMock) -> None:
    """After AuthError sets fatal, further on_message calls are no-ops."""
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    httpx_mock.add_response(url=cfg.base_url + "/internal/tasks/1/claim", status_code=401)
    dispatcher = TaskDispatcher(cfg, http)
    try:
        await dispatcher.on_message(_msg(task_id=1))
        # Drain the spawned CLAIM task by awaiting it directly.
        await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)
        assert dispatcher.is_fatal is True
        # New message should be dropped without any further HTTP.
        before = len(httpx_mock.get_requests())
        await dispatcher.on_message(_msg(task_id=2))
        assert dispatcher.in_flight_count() == 0
        assert len(httpx_mock.get_requests()) == before
    finally:
        await http.close()


async def test_in_flight_count_tracks_dispatches(httpx_mock: HTTPXMock) -> None:
    """in_flight_count reflects active asyncio tasks."""
    cfg = _cfg()
    # Block the claim so the task stays in-flight.
    blocker = asyncio.Event()

    async def slow_claim(*_a: Any, **_kw: Any) -> dict[str, Any]:
        await blocker.wait()
        return {}

    async def fake_report(*_a: Any, **_kw: Any) -> dict[str, Any]:
        return {}

    http = PlatformHttpClient(cfg)
    http.claim = slow_claim  # type: ignore[method-assign]
    http.report = fake_report  # type: ignore[method-assign]
    dispatcher = TaskDispatcher(cfg, http)
    try:
        await dispatcher.on_message(_msg(task_id=100))
        await asyncio.sleep(0)  # let task start
        assert dispatcher.in_flight_count() == 1
        assert 100 in dispatcher.in_flight_task_ids()
        blocker.set()
        await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)
        assert dispatcher.in_flight_task_ids() == set()
    finally:
        await http.close()


async def test_shutdown_timeout_returns_when_drained(httpx_mock: HTTPXMock) -> None:
    """shutdown() drains within the timeout when tasks finish fast."""
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
    """Directive runtimeState=DRAINING → dispatcher refuses new tasks."""
    dispatcher, http = await _make()
    try:
        dispatcher.apply_platform_directive({"runtimeState": "DRAINING"})
        assert dispatcher.runtime_state == WorkerRuntimeState.DRAINING
        assert dispatcher.accepts_new_tasks() is False
        await dispatcher.on_message(_msg(task_id=7))
        assert dispatcher.in_flight_count() == 0
    finally:
        await http.close()


async def test_unsupported_schema_version_drops(
    caplog: pytest.LogCaptureFixture, httpx_mock: HTTPXMock
) -> None:
    """schemaVersion=v3 → rejected (forward-compat safety)."""
    dispatcher, http = await _make()
    try:
        caplog.set_level("WARNING", logger="batch_worker_sdk.dispatcher")
        msg = _msg(task_id=5)
        msg["schemaVersion"] = "v3"
        await dispatcher.on_message(msg)
        assert dispatcher.in_flight_count() == 0
        assert any("unsupported schemaVersion" in r.message for r in caplog.records)
    finally:
        await http.close()
