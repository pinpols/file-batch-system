"""Testkit-driven contract integration (P5 PoC).

The sister file :mod:`test_contract_runner` exercises fixtures against
``pytest_httpx`` mocks — fast but synthetic. This file proves the
same fixtures pass against a **real** in-process platform fake
(:class:`FakeBatchPlatform`) using :class:`PlatformHttpClient` for
true end-to-end HTTP. Lane V scope ships 2 fixtures as PoC
(``01-register-success`` + ``03-heartbeat-directive-normal``); the
remaining 9 land in P5 follow-up alongside Lane T (real client +
scheduler).
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
_REPO_ROOT = Path(__file__).resolve().parents[3]
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
    """Drives ``01-register-success`` end-to-end through PlatformHttpClient."""
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
    """Drives ``03-heartbeat-directive-normal`` end-to-end."""
    fixture = _load("03-heartbeat-directive-normal")
    body = fixture["when"]["body"]
    # Fake echoes whatever directive we set — use the documented payload.
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

    # Spot-check at least one documented directive field.
    for key, value in directive.items():
        assert result.get(key) == value, f"mismatch on {key!r}"
    assert len(fp.get_heartbeats()) == 1
