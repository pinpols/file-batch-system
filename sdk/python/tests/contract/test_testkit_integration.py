"""Testkit 驱动的 contract 集成测试(P5 PoC)。

兄弟文件 :mod:`test_contract_runner` 用 ``pytest_httpx`` mock 跑 fixture
—— 快但合成。本文件证明同样的 fixture 也能在 **真实** 的进程内平台
fake(:class:`FakeBatchPlatform`)上通过 :class:`PlatformHttpClient`
做真端到端 HTTP。当前 PoC 范围只发 2 个 fixture(``01-register-success``
+ ``03-heartbeat-directive-normal``);剩余 9 个会在 P5 后续随真实
client + scheduler 一起落地。
"""

from __future__ import annotations

import json
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest

from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.testkit import FakeBatchPlatform, make_test_config

# <repo>/sdk-python/tests/contract/<file> -> <repo>
_REPO_ROOT = Path(__file__).resolve().parents[4]
_FIXTURES_DIR = _REPO_ROOT / "docs" / "api" / "sdk-contract-fixtures"


def _load(name: str) -> dict[str, Any]:
    return json.loads((_FIXTURES_DIR / f"{name}.json").read_text(encoding="utf-8"))


@pytest.fixture
async def fp():
    platform = FakeBatchPlatform()
    await platform.start()
    try:
        yield platform
    finally:
        await platform.stop()


@pytest.mark.contract
async def test_register_success_against_fake(fp: FakeBatchPlatform) -> None:
    """通过 PlatformHttpClient 端到端跑完 ``01-register-success``。"""
    fixture = _load("01-register-success")
    body = fixture["when"]["body"]
    cfg = make_test_config(
        base_url=fp.base_url,
        tenant_id=body["tenantId"],
        worker_code=body["workerCode"],
        retry_base_delay=timedelta(milliseconds=1),
    )
    async with PlatformHttpClient(cfg) as client:
        result = await client.register(body)

    assert result["registered"] is True
    regs = fp.get_registrations()
    assert len(regs) == 1
    assert regs[0]["workerCode"] == "w-1"
    assert regs[0]["tenantId"] == "acme"


@pytest.mark.contract
async def test_heartbeat_directive_normal_against_fake(
    fp: FakeBatchPlatform,
) -> None:
    """端到端跑完 ``03-heartbeat-directive-normal``。"""
    fixture = _load("03-heartbeat-directive-normal")
    body = fixture["when"]["body"]
    # Fake 会原样回放我们设置的 directive —— 用文档化的 payload。
    directive = fixture["when"]["responseBody"]
    fp.set_heartbeat_directive(directive)

    cfg = make_test_config(
        base_url=fp.base_url,
        tenant_id="acme",
        worker_code="w-1",
        retry_base_delay=timedelta(milliseconds=1),
    )
    async with PlatformHttpClient(cfg) as client:
        result = await client.heartbeat("w-1", body)

    # 至少抽查一个文档化的 directive 字段。
    for key, value in directive.items():
        assert result.get(key) == value, f"mismatch on {key!r}"
    assert len(fp.get_heartbeats()) == 1
