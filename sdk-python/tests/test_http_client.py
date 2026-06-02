"""HTTP-layer behaviour tests for :class:`PlatformHttpClient`.

Uses ``pytest_httpx`` to mock the platform; tests focus on:

- request shape (path / headers / body) for each of the 8 endpoints
- retry/backoff integration on 5xx
- idempotency-key propagation for claim / report
- 409 surfaced as normal return (not raised)
- 401 raises ``AuthError`` without retry
- ``close()`` lifecycle
"""

from __future__ import annotations

import pytest
from pytest_httpx import HTTPXMock

from batch_worker_sdk import AuthError, BatchPlatformClientConfig
from batch_worker_sdk._http import PlatformHttpClient
from batch_worker_sdk.exceptions import PersistentClientError, TransientError

_BASE = "http://orch:8081"


def _cfg(**overrides) -> BatchPlatformClientConfig:
    kwargs = {
        "base_url": _BASE,
        "tenant_id": "acme",
        "worker_code": "w-1",
        "api_key": "sekret",
    }
    kwargs.update(overrides)
    return BatchPlatformClientConfig(**kwargs)


@pytest.fixture(name="client")
async def _client(httpx_mock: HTTPXMock):
    c = PlatformHttpClient(_cfg())
    try:
        yield c
    finally:
        await c.close()


async def test_register_posts_to_workers_register(
    client: PlatformHttpClient, httpx_mock: HTTPXMock
):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/workers/register",
        method="POST",
        json={"id": 42, "workerCode": "w-1", "status": "ONLINE"},
    )
    out = await client.register({"tenantId": "acme", "workerCode": "w-1"})
    assert out["id"] == 42
    req = httpx_mock.get_request()
    assert req is not None
    assert req.headers["X-Batch-Tenant-Id"] == "acme"
    assert req.headers["X-Batch-Api-Key"] == "sekret"
    assert "Idempotency-Key" not in req.headers


async def test_heartbeat_posts_to_worker_code_path(
    client: PlatformHttpClient, httpx_mock: HTTPXMock
):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/workers/w-1/heartbeat",
        method="POST",
        json={"platformStatus": "NORMAL", "shouldDrain": False},
    )
    out = await client.heartbeat("w-1", {"currentLoad": 0})
    assert out["platformStatus"] == "NORMAL"


async def test_deactivate_tolerates_empty_response(
    client: PlatformHttpClient, httpx_mock: HTTPXMock
):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/workers/w-1/deactivate",
        method="POST",
        status_code=200,
        content=b"",
    )
    # deactivate returns None — must not raise on empty body
    await client.deactivate("w-1", {"status": "OFFLINE"})


async def test_claim_propagates_idempotency_key(client: PlatformHttpClient, httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/tasks/12345/claim",
        method="POST",
        json={"taskId": 12345, "config": {}},
    )
    await client.claim(12345, "claim-key-xyz", {"workerId": "w-1"})
    req = httpx_mock.get_request()
    assert req is not None
    assert req.headers["Idempotency-Key"] == "claim-key-xyz"


async def test_claim_409_returns_body_not_raises(client: PlatformHttpClient, httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/tasks/12345/claim",
        method="POST",
        status_code=409,
        json={"code": "ALREADY_CLAIMED", "message": "claimed by w-2"},
    )
    out = await client.claim(12345, "k", {"workerId": "w-1"})
    # 409 surfaces as normal return per wire-protocol §B
    assert out["code"] == "ALREADY_CLAIMED"


async def test_claim_401_raises_auth_error_no_retry(
    client: PlatformHttpClient, httpx_mock: HTTPXMock
):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/tasks/12345/claim",
        method="POST",
        status_code=401,
        json={"code": "AUTH_INVALID", "traceId": "t-1"},
    )
    with pytest.raises(AuthError) as ei:
        await client.claim(12345, "k", {"workerId": "w-1"})
    assert ei.value.request_id == "t-1"
    # exactly one request was made — no retry
    assert len(httpx_mock.get_requests()) == 1


async def test_report_5xx_retries_with_backoff(httpx_mock: HTTPXMock):
    cfg = _cfg(retry_base_delay=__import__("datetime").timedelta(milliseconds=1))
    client = PlatformHttpClient(cfg)
    try:
        for _ in range(3):
            httpx_mock.add_response(
                url=f"{_BASE}/internal/tasks/12345/report",
                method="POST",
                status_code=503,
                json={"code": "PLATFORM_UNAVAILABLE"},
            )
        with pytest.raises(TransientError) as ei:
            await client.report(12345, "k", {"success": True})
        assert ei.value.status_code == 503
        assert ei.value.attempts == 3
        assert len(httpx_mock.get_requests()) == 3
    finally:
        await client.close()


async def test_report_4xx_validation_raises_persistent(
    client: PlatformHttpClient, httpx_mock: HTTPXMock
):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/tasks/12345/report",
        method="POST",
        status_code=422,
        json={"code": "VALIDATION_FAILED"},
    )
    with pytest.raises(PersistentClientError):
        await client.report(12345, "k", {"success": True})


async def test_renew_posts_to_tasks_renew(client: PlatformHttpClient, httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/tasks/12345/renew",
        method="POST",
        json={"cancelRequested": True},
    )
    out = await client.renew(12345, {"workerId": "w-1"})
    assert out["cancelRequested"] is True


async def test_get_status_uses_get(client: PlatformHttpClient, httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/workers/w-1/status",
        method="GET",
        json={"workerCode": "w-1", "status": "ONLINE"},
    )
    out = await client.get_status("w-1")
    assert out["status"] == "ONLINE"


async def test_close_idempotent(httpx_mock: HTTPXMock):
    c = PlatformHttpClient(_cfg())
    await c.close()
    # second close should not raise (httpx already closed)
    await c.close()


async def test_async_context_manager_closes_client(httpx_mock: HTTPXMock):
    httpx_mock.add_response(
        url=f"{_BASE}/internal/workers/register",
        method="POST",
        json={},
    )
    async with PlatformHttpClient(_cfg()) as c:
        await c.register({})
