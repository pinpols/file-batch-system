"""Heartbeat wire 字段契约测试(P0-4 守护)。

锁定 Python SDK ``HeartbeatScheduler.tick`` 发出的 HTTP body 形状:

- 必填 5 字段:``tenantId`` / ``workerCode`` / ``status`` / ``heartbeatAt``
  / ``currentLoad``。
- P0-4 新增 6 字段:``workerGroup`` / ``hostName`` / ``hostIp`` /
  ``processId`` / ``capabilityTags`` / ``buildId`` —— 平台心跳路径共用
  ``WorkerHeartbeatDto`` 会刷新 worker_registry 运维元数据列;不发会被
  静默覆盖为 NULL,控制台 "我的 Worker" 看不到 IP / 主机 / 能力标签。

fixture 来源:``docs/api/sdk-contract-fixtures/03-heartbeat-directive-normal.json``
(平台返回侧 directive 形状)— 本测试的请求侧字段是 Java
``HeartbeatRequest`` record 的 Python 对位实现。
"""

from __future__ import annotations

import json
import os
import re
from datetime import timedelta
from unittest.mock import MagicMock

import pytest
from pytest_httpx import HTTPXMock

from batch_worker_sdk import BatchPlatformClientConfig
from batch_worker_sdk.internal import _fingerprint
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.scheduler._heartbeat import HeartbeatScheduler

_BASE = "http://orch:8081"
_TENANT = "acme"
_WORKER = "w-1"

_ISO_INSTANT_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$"
)


def _cfg(*, build_id: str | None = "build-2026-06-03") -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url=_BASE,
        tenant_id=_TENANT,
        worker_code=_WORKER,
        build_id=build_id,
        retry_base_delay=timedelta(milliseconds=1),
    )


@pytest.fixture
async def http_client(httpx_mock: HTTPXMock):
    client = PlatformHttpClient(_cfg())
    try:
        yield client
    finally:
        await client.close()


def _make_scheduler(
    http: PlatformHttpClient,
    *,
    cfg: BatchPlatformClientConfig | None = None,
    capability_tags: list[str] | None = None,
    in_flight: int = 0,
) -> HeartbeatScheduler:
    dispatcher = MagicMock()
    dispatcher.in_flight_count.return_value = in_flight
    dispatcher.in_flight_task_ids.return_value = set()
    return HeartbeatScheduler(
        cfg or _cfg(),
        http,
        dispatcher,
        capability_tags=capability_tags or ["demo-task"],
    )


# ---------------------------------------------------------------------------
# 基础 5 字段(原始契约)
# ---------------------------------------------------------------------------


async def test_heartbeat_body_contains_baseline_fields(
    httpx_mock: HTTPXMock,
    http_client: PlatformHttpClient,
) -> None:
    httpx_mock.add_response(
        url=f"{_BASE}/internal/workers/{_WORKER}/heartbeat",
        method="POST",
        json={"platformStatus": "NORMAL"},
    )

    scheduler = _make_scheduler(http_client, in_flight=2)
    await scheduler.tick()

    body = json.loads(httpx_mock.get_request().content)
    assert body["tenantId"] == _TENANT
    assert body["workerCode"] == _WORKER
    assert body["status"] == "RUNNING"
    assert body["currentLoad"] == 2
    assert _ISO_INSTANT_RE.match(body["heartbeatAt"]), body["heartbeatAt"]


# ---------------------------------------------------------------------------
# P0-4:补齐 6 个 worker_registry 运维列
# ---------------------------------------------------------------------------


async def test_heartbeat_body_contains_p0_4_fields(
    httpx_mock: HTTPXMock,
    http_client: PlatformHttpClient,
) -> None:
    """P0-4:6 个 worker_registry 运维列必须随每次心跳上报。"""
    httpx_mock.add_response(
        url=f"{_BASE}/internal/workers/{_WORKER}/heartbeat",
        method="POST",
        json={"platformStatus": "NORMAL"},
    )

    scheduler = _make_scheduler(
        http_client,
        capability_tags=["import-csv", "process-rows"],
    )
    await scheduler.tick()

    body = json.loads(httpx_mock.get_request().content)
    assert body["workerGroup"] == "sdk-self-hosted"
    assert body["capabilityTags"] == ["import-csv", "process-rows"]
    # process_id 总能采到(os.getpid())
    assert body["processId"] == str(os.getpid())
    assert body["buildId"] == "build-2026-06-03"
    # hostName / hostIp 尽力而为;能采到则上报。CI 环境一般 socket.gethostname()
    # 都能成功,因此断言 key 存在(若失败应在 fingerprint 单测捕获)。
    assert body.get("hostName") == _fingerprint.host_name()
    if _fingerprint.host_ip() is not None:
        assert body["hostIp"] == _fingerprint.host_ip()


async def test_heartbeat_omits_build_id_when_unset(
    httpx_mock: HTTPXMock,
) -> None:
    """build_id 未配置时不应发空字符串(否则平台列覆盖为空)。"""
    cfg = _cfg(build_id=None)
    client = PlatformHttpClient(cfg)
    try:
        httpx_mock.add_response(
            url=f"{_BASE}/internal/workers/{_WORKER}/heartbeat",
            method="POST",
            json={"platformStatus": "NORMAL"},
        )
        scheduler = _make_scheduler(client, cfg=cfg)
        await scheduler.tick()
        body = json.loads(httpx_mock.get_request().content)
        assert "buildId" not in body
    finally:
        await client.close()


async def test_heartbeat_omits_host_fields_when_unresolvable(
    httpx_mock: HTTPXMock,
    http_client: PlatformHttpClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """hostName / hostIp 采集失败时必须 elide,绝不发 None / 空串。"""
    monkeypatch.setattr(_fingerprint, "host_name", lambda: None)
    monkeypatch.setattr(_fingerprint, "host_ip", lambda: None)

    httpx_mock.add_response(
        url=f"{_BASE}/internal/workers/{_WORKER}/heartbeat",
        method="POST",
        json={"platformStatus": "NORMAL"},
    )
    scheduler = _make_scheduler(http_client)
    await scheduler.tick()
    body = json.loads(httpx_mock.get_request().content)
    assert "hostName" not in body
    assert "hostIp" not in body
    # process_id 总是有
    assert "processId" in body


# ---------------------------------------------------------------------------
# fingerprint 模块自测
# ---------------------------------------------------------------------------


def test_fingerprint_process_id_is_current_pid() -> None:
    assert _fingerprint.process_id() == str(os.getpid())


def test_fingerprint_host_name_non_blank_in_normal_env() -> None:
    """正常 CI / 本地环境 socket.gethostname() 都能成功返回。"""
    name = _fingerprint.host_name()
    # 允许 None(极端容器)但不允许空串
    assert name is None or name.strip() != ""
