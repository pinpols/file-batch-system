""":class:`FakeBatchPlatform` 单元测试。

镜像 Java 侧的 ``FakeBatchPlatformSelfTest``。直接通过
``httpx.AsyncClient`` 驱动 fake(不依赖 SDK 的 ``BatchPlatformClient``,
后者由 client 模块负责),这样无论 client 模块是否就位都不影响本文件。
"""

from __future__ import annotations

import httpx
import pytest

from batch_worker_sdk.testkit import FakeBatchPlatform


@pytest.fixture
async def platform():
    fp = FakeBatchPlatform()
    await fp.start()
    try:
        yield fp
    finally:
        await fp.stop()


async def test_start_returns_base_url_and_stop_is_idempotent() -> None:
    fp = FakeBatchPlatform()
    url = await fp.start()
    assert url.startswith("http://127.0.0.1:")
    assert fp.base_url == url
    await fp.stop()
    await fp.stop()  # 幂等
    # stop 之后访问 base_url 必须显式失败 —— 没有 server 可打。
    with pytest.raises(RuntimeError):
        _ = fp.base_url


async def test_double_start_rejected() -> None:
    fp = FakeBatchPlatform()
    await fp.start()
    try:
        with pytest.raises(RuntimeError):
            await fp.start()
    finally:
        await fp.stop()


async def test_register_records_body(platform: FakeBatchPlatform) -> None:
    async with httpx.AsyncClient(base_url=platform.base_url) as c:
        resp = await c.post(
            "/internal/workers/register",
            json={"workerCode": "w-1", "tenantId": "acme"},
        )
    assert resp.status_code == 200
    assert resp.json() == {"workerCode": "w-1", "registered": True}
    regs = platform.get_registrations()
    assert len(regs) == 1
    assert regs[0]["workerCode"] == "w-1"


async def test_heartbeat_returns_directive(platform: FakeBatchPlatform) -> None:
    platform.set_heartbeat_directive({"runtimeState": "DEGRADED", "maxConcurrent": 2})
    async with httpx.AsyncClient(base_url=platform.base_url) as c:
        resp = await c.post("/internal/workers/w-1/heartbeat", json={"inFlight": 0})
    assert resp.json() == {"runtimeState": "DEGRADED", "maxConcurrent": 2}
    hbs = platform.get_heartbeats()
    assert len(hbs) == 1
    assert hbs[0]["inFlight"] == 0


async def test_renew_cancel_only_for_marked_task(
    platform: FakeBatchPlatform,
) -> None:
    platform.set_cancel_for_task(42)
    async with httpx.AsyncClient(base_url=platform.base_url) as c:
        cancelled = await c.post("/internal/tasks/42/renew", json={})
        normal = await c.post("/internal/tasks/43/renew", json={})
    assert cancelled.json() == {"cancelRequested": True}
    assert normal.json() == {"cancelRequested": False}
    assert {r["_taskId"] for r in platform.get_renews()} == {42, 43}


async def test_claim_records_task_id(platform: FakeBatchPlatform) -> None:
    async with httpx.AsyncClient(base_url=platform.base_url) as c:
        resp = await c.post("/internal/tasks/100/claim", json={"workerCode": "w-1"})
    assert resp.json() == {"taskId": 100, "claimed": True}
    claims = platform.get_claims()
    assert len(claims) == 1
    assert claims[0]["_taskId"] == 100


async def test_report_recorded(platform: FakeBatchPlatform) -> None:
    async with httpx.AsyncClient(base_url=platform.base_url) as c:
        await c.post(
            "/internal/tasks/7/report",
            json={"success": True, "outputs": {"k": "v"}},
        )
    reports = platform.get_reports()
    assert len(reports) == 1
    assert reports[0]["taskId"] == 7
    assert reports[0]["success"] is True


async def test_deactivate_and_drain_recorded(platform: FakeBatchPlatform) -> None:
    async with httpx.AsyncClient(base_url=platform.base_url) as c:
        await c.post("/internal/workers/w-1/deactivate", json={"reason": "shutdown"})
        await c.post("/internal/workers/w-1/drain", json={"timeoutSec": 30})
    assert platform.get_deactivates()[0]["reason"] == "shutdown"
    assert platform.get_drains()[0]["timeoutSec"] == 30


async def test_status_endpoint(platform: FakeBatchPlatform) -> None:
    async with httpx.AsyncClient(base_url=platform.base_url) as c:
        resp = await c.get("/internal/workers/w-1/status")
    assert resp.json()["state"] == "NORMAL"


async def test_dispatch_task_queue_and_take(platform: FakeBatchPlatform) -> None:
    assert platform.pending_dispatches() == 0
    platform.dispatch_task({"taskId": 1, "taskType": "echo"})
    platform.dispatch_task({"taskId": 2, "taskType": "echo"})
    assert platform.pending_dispatches() == 2
    first = await platform.take_dispatch()
    assert first["taskId"] == 1
    assert platform.pending_dispatches() == 1


async def test_take_dispatch_times_out(platform: FakeBatchPlatform) -> None:
    with pytest.raises(TimeoutError):
        await platform.take_dispatch(timeout_s=0.05)


async def test_async_context_manager() -> None:
    async with FakeBatchPlatform() as fp:
        url = fp.base_url
        async with httpx.AsyncClient(base_url=url) as c:
            resp = await c.post("/internal/workers/register", json={"workerCode": "ctx"})
        assert resp.status_code == 200
        assert len(fp.get_registrations()) == 1
