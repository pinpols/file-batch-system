"""Contract-fixture runner.

Discovers every JSON fixture published by Lane N under
``docs/api/sdk-contract-fixtures/`` and exercises the SDK against it.

Phase split
-----------
Lane Q (this PR, P1) implements the pure HTTP-layer behaviours:
register / heartbeat / claim / report / renew end-to-end through
``PlatformHttpClient`` against a mocked platform. Each fixture's
``when.responseStatus`` + ``responseBody`` is loaded into ``pytest_httpx``,
the corresponding SDK method is invoked, and we assert the documented
classification (success / AuthError / idempotent-success-on-409 / retry
+ TransientError on 5xx).

Lane Q does **not** drive Kafka-channel fixtures or fixtures that
require the FSM / scheduler to enact a side effect beyond the HTTP
response (e.g. "pause Kafka assignment", "drain and deactivate then
exit"). Those stay ``xfail`` and are claimed by:

- P2 (Kafka consumer)  : 11-kafka-partition-pause-on-capacity
- P4 (FSM stop/drain)  : 12-stop-with-timeout

The directive enactment side of 03-06 (e.g. "transition FSM to
PAUSED") also lands in P3-P4; here we only verify the HTTP request/
response shape, which is what most BYO SDK implementers actually need
to copy-paste off.
"""

from __future__ import annotations

import json
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest
from pytest_httpx import HTTPXMock

from batch_worker_sdk import (
    AuthError,
    BatchPlatformClientConfig,
)
from batch_worker_sdk._http import PlatformHttpClient
from batch_worker_sdk.exceptions import TransientError

# <repo>/sdk-python/tests/contract/test_contract_runner.py -> <repo>
_REPO_ROOT = Path(__file__).resolve().parents[3]
_FIXTURES_DIR = _REPO_ROOT / "docs" / "api" / "sdk-contract-fixtures"

# ---------------------------------------------------------------------------
# Phase ownership map — keep in sync with module docstring.
# ---------------------------------------------------------------------------
_P1_HTTP_FIXTURES: set[str] = {
    "01-register-success",
    "02-register-conflict-idempotent",
    "03-heartbeat-directive-normal",
    "04-heartbeat-directive-draining",
    "05-heartbeat-directive-paused",
    "06-heartbeat-next-interval-hint",
    "07-claim-401-fail-fast",
    "08-claim-409-idempotent-success",
    "09-report-5xx-retry-backoff",
    "10-renew-cancel-requested",
    # 12-stop-with-timeout: only the /deactivate HTTP request is
    # verifiable here; the FSM drain timeline lands in P4.
    "12-stop-with-timeout",
}

# P2+ — Kafka / FSM driven; cannot be verified at the HTTP layer.
_DEFERRED_FIXTURES: set[str] = {
    "11-kafka-partition-pause-on-capacity",
}


def _discover_fixtures() -> list[Path]:
    """Return every contract fixture, skipping Lane P's drift-guard
    metadata files (``fixture-schema.json`` etc.) which live in the same
    directory but are JSON Schemas — not fixture envelopes."""
    if not _FIXTURES_DIR.is_dir():
        return []
    skip = {"fixture-schema"}
    return sorted(p for p in _FIXTURES_DIR.glob("*.json") if p.is_file() and p.stem not in skip)


_FIXTURES = _discover_fixtures()
_FIXTURE_IDS = [p.stem for p in _FIXTURES]


def _load_payloads() -> dict[str, dict[str, Any]]:
    """Pre-load every fixture once at import time.

    Avoids ASYNC240 (no sync I/O inside the async test body) and keeps
    test bodies focused on assertions.
    """
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in _FIXTURES}


_PAYLOADS = _load_payloads()


def _cfg_from_fixture(fixture_cfg: dict[str, Any]) -> BatchPlatformClientConfig:
    """Build a valid SDK config from the fixture's ``given.config`` block.

    Fixtures intentionally omit fields irrelevant to the scenario, so we
    fill in safe defaults. Retry delay is forced to 1ms to keep 5xx-retry
    fixtures fast.
    """
    kwargs: dict[str, Any] = {
        "base_url": fixture_cfg.get("baseUrl") or "http://orch:8081",
        "tenant_id": fixture_cfg.get("tenantId", "acme"),
        "worker_code": fixture_cfg.get("workerCode", "w-1"),
        "api_key": fixture_cfg.get("apiKey"),
        "retry_base_delay": timedelta(milliseconds=1),
    }
    if "retryMaxAttempts" in fixture_cfg:
        kwargs["retry_max_attempts"] = int(fixture_cfg["retryMaxAttempts"])
    return BatchPlatformClientConfig(**kwargs)


async def _invoke(client: PlatformHttpClient, when: dict[str, Any]) -> dict[str, Any] | None:
    """Dispatch the fixture's HTTP call to the matching SDK method."""
    path: str = when["path"]
    body: dict[str, Any] = when.get("body") or {}

    if path.endswith("/register"):
        return await client.register(body)
    if path.endswith("/heartbeat"):
        worker_code = path.split("/internal/workers/")[1].split("/", maxsplit=1)[0]
        return await client.heartbeat(worker_code, body)
    if path.endswith("/deactivate"):
        worker_code = path.split("/internal/workers/")[1].split("/", maxsplit=1)[0]
        await client.deactivate(worker_code, body)
        return None
    if path.endswith("/claim"):
        task_id = int(path.split("/internal/tasks/")[1].split("/", maxsplit=1)[0])
        return await client.claim(task_id, f"claim-{task_id}", body)
    if path.endswith("/report"):
        task_id = int(path.split("/internal/tasks/")[1].split("/", maxsplit=1)[0])
        return await client.report(task_id, f"report-{task_id}", body)
    if path.endswith("/renew"):
        task_id = int(path.split("/internal/tasks/")[1].split("/", maxsplit=1)[0])
        return await client.renew(task_id, body)
    raise NotImplementedError(f"path not routed: {path}")


@pytest.mark.contract
@pytest.mark.parametrize("fixture_path", _FIXTURES, ids=_FIXTURE_IDS)
async def test_contract_fixture(
    fixture_path: Path,
    httpx_mock: HTTPXMock,
    request: pytest.FixtureRequest,
) -> None:
    """Run one contract fixture through the SDK and verify HTTP behaviour.

    Deferred fixtures (Kafka / FSM-stop) are skipped with ``xfail`` per
    the phase map at the top of this file.
    """
    fixture_id = fixture_path.stem
    if fixture_id in _DEFERRED_FIXTURES:
        request.applymarker(
            pytest.mark.xfail(
                strict=True,
                reason=(
                    "deferred to P2 (Kafka) / P4 (FSM stop) — see test_contract_runner.py phase map"
                ),
            )
        )

    payload = _PAYLOADS[fixture_id]
    given = payload.get("given") or {}
    when = payload.get("when") or {}

    if when.get("channel") != "http":
        # Kafka-channel fixtures are xfailed above; this assertion
        # catches any future non-http channel before it silently fails.
        pytest.fail(f"non-http channel fixture: {fixture_id} channel={when.get('channel')!r}")

    cfg = _cfg_from_fixture(given.get("config") or {})
    status = when["responseStatus"]
    body = when.get("responseBody")

    # 5xx-retry fixture needs the same 5xx returned `retry_max_attempts`
    # times (the SDK will retry that many).
    response_count = cfg.retry_max_attempts if status and status >= 500 else 1
    for _ in range(response_count):
        kwargs: dict[str, Any] = {
            "url": cfg.base_url + when["path"],
            "method": when["method"],
            "status_code": status,
        }
        if body is None:
            kwargs["content"] = b""
        else:
            kwargs["json"] = body
        httpx_mock.add_response(**kwargs)

    client = PlatformHttpClient(cfg)
    try:
        await _assert_fixture(client, when, status, body, cfg, httpx_mock, fixture_id)
    finally:
        await client.close()


async def _assert_fixture(
    client: PlatformHttpClient,
    when: dict[str, Any],
    status: int,
    body: dict[str, Any] | None,
    cfg: BatchPlatformClientConfig,
    httpx_mock: HTTPXMock,
    fixture_id: str,
) -> None:
    if status in (401, 403):
        with pytest.raises(AuthError):
            await _invoke(client, when)
        assert len(httpx_mock.get_requests()) == 1, "401/403 must not retry"
        return

    if status and status >= 500:
        with pytest.raises(TransientError) as ei:
            await _invoke(client, when)
        assert ei.value.attempts == cfg.retry_max_attempts
        assert len(httpx_mock.get_requests()) == cfg.retry_max_attempts
        return

    result = await _invoke(client, when)

    if status == 409:
        # idempotent success: response body is returned, no error raised
        assert isinstance(result, dict)
        if body and "code" in body:
            assert result.get("code") == body["code"]
        return

    if 200 <= status < 300:
        if body is None or result is None:
            return  # deactivate / empty body
        for k, v in body.items():
            assert result.get(k) == v, f"field {k} mismatch in {fixture_id}"


def test_fixture_discovery_reports_count(capsys: pytest.CaptureFixture[str]) -> None:
    """Diagnostic test — prints the fixture inventory and phase split."""
    count = len(_FIXTURES)
    p1 = sorted(f for f in _FIXTURE_IDS if f in _P1_HTTP_FIXTURES)
    deferred = sorted(f for f in _FIXTURE_IDS if f in _DEFERRED_FIXTURES)
    other = sorted(
        f for f in _FIXTURE_IDS if f not in _P1_HTTP_FIXTURES and f not in _DEFERRED_FIXTURES
    )
    if count == 0:
        print(f"[contract] 0 fixtures discovered at {_FIXTURES_DIR}")
    else:
        print(
            f"[contract] {count} fixtures discovered; "
            f"P1-implemented={len(p1)}, deferred={len(deferred)}, other={len(other)}"
        )
        print(f"[contract] P1: {p1}")
        print(f"[contract] deferred: {deferred}")
        if other:
            print(f"[contract] other: {other}")
    assert count >= 0
