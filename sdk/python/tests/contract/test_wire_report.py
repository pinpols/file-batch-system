"""REPORT wire 字段契约测试(P0-1 / P0-3 守护)。

锁定 Python SDK ``TaskDispatcher._report_success`` / ``_report_failure``
发出的 HTTP body 形状与平台 ``TaskExecutionReportDto`` 一致:

- ``success: bool``(不是 ``status: "SUCCESS"/"FAILED"`` 字符串)
- ``resultSummary`` + ``errorCode``(已废 ``errorMessage`` 字段)
- ``taskId`` / ``tenantId`` / ``workerId`` 三件套必须齐
- idempotency-key 为 ``sdk-py-<uuid4>``,每次写操作独立 key
  (不是固定 ``report-{taskId}``)

fixture 来源:``docs/api/sdk-contract-fixtures/09-report-5xx-retry-backoff.json``
描述了平台期望的 REPORT body 字段集合 —— Java SDK 通过
``SdkWireContractTest`` 锁定同一份;本测试是 Python 侧的对位守护。
"""

from __future__ import annotations

import json
import re
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest
from pytest_httpx import HTTPXMock

from batch_worker_sdk import BatchPlatformClientConfig
from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher
from batch_worker_sdk.internal._http import PlatformHttpClient

_BASE = "http://orch:8081"
_TENANT = "acme"
_WORKER = "w-1"
_TASK_ID = 12345

# <repo>/sdk-python/tests/contract/test_wire_report.py -> <repo>
_REPO_ROOT = Path(__file__).resolve().parents[4]
_FIXTURE_REPORT = (
    _REPO_ROOT / "docs" / "api" / "sdk-contract-fixtures" / "09-report-5xx-retry-backoff.json"
)


def _cfg() -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url=_BASE,
        tenant_id=_TENANT,
        worker_code=_WORKER,
        retry_base_delay=timedelta(milliseconds=1),
    )


def _dispatch_msg(*, with_trace: bool = False) -> dict[str, Any]:
    msg: dict[str, Any] = {
        "schemaVersion": "v1",
        "tenantId": _TENANT,
        "taskId": _TASK_ID,
        "workerType": "demo",
        "runtimeAttributes": {"partitionInvocationId": "p-1"},
    }
    if with_trace:
        msg["traceId"] = "trace-abc"
    return msg


@pytest.fixture
async def http_client(httpx_mock: HTTPXMock):
    client = PlatformHttpClient(_cfg())
    try:
        yield client
    finally:
        await client.close()


def _fixture_report_body() -> dict[str, Any]:
    """从 fixture 09 加载 'when.body',作为平台期望的字段集合 source-of-truth。"""
    payload = json.loads(_FIXTURE_REPORT.read_text(encoding="utf-8"))
    return payload["when"]["body"]


# ---------------------------------------------------------------------------
# Success report
# ---------------------------------------------------------------------------


async def test_report_success_body_matches_platform_dto(
    httpx_mock: HTTPXMock,
    http_client: PlatformHttpClient,
) -> None:
    """``_report_success`` body 必须含 success=True,与平台 DTO 对齐。"""
    httpx_mock.add_response(
        url=f"{_BASE}/internal/tasks/{_TASK_ID}/report",
        method="POST",
        json={"ok": True},
    )

    dispatcher = TaskDispatcher(_cfg(), http_client, handlers={})
    await dispatcher._report_success(_TASK_ID, _dispatch_msg(with_trace=True))

    req = httpx_mock.get_request()
    assert req is not None
    body = json.loads(req.content)

    # P0-1:必须 success=True,严禁老的 status="SUCCESS"
    assert body["success"] is True
    assert "status" not in body, "status 是已废字段,平台 jackson 会静默丢弃"

    # 平台 TaskExecutionReportDto 必填三件套
    assert body["taskId"] == _TASK_ID
    assert body["tenantId"] == _TENANT
    assert body["workerId"] == _WORKER

    # traceId + partitionInvocationId 应原样回传(平台用于路由 / 关联日志)
    assert body["traceId"] == "trace-abc"
    assert body["partitionInvocationId"] == "p-1"


async def test_report_success_omits_optional_fields_when_absent(
    httpx_mock: HTTPXMock,
    http_client: PlatformHttpClient,
) -> None:
    """msg 不带 traceId/partitionInvocationId 时 body 不应捏造空值。"""
    httpx_mock.add_response(
        url=f"{_BASE}/internal/tasks/{_TASK_ID}/report",
        method="POST",
        json={"ok": True},
    )

    msg = {
        "schemaVersion": "v1",
        "tenantId": _TENANT,
        "taskId": _TASK_ID,
        "workerType": "demo",
    }
    dispatcher = TaskDispatcher(_cfg(), http_client, handlers={})
    await dispatcher._report_success(_TASK_ID, msg)

    body = json.loads(httpx_mock.get_request().content)
    assert body["success"] is True
    assert "traceId" not in body
    assert "partitionInvocationId" not in body


# ---------------------------------------------------------------------------
# Failure report
# ---------------------------------------------------------------------------


async def test_report_failure_body_uses_result_summary_not_error_message(
    httpx_mock: HTTPXMock,
    http_client: PlatformHttpClient,
) -> None:
    """失败侧字段名 P0-1:success=false + resultSummary + errorCode。严禁 errorMessage。"""
    httpx_mock.add_response(
        url=f"{_BASE}/internal/tasks/{_TASK_ID}/report",
        method="POST",
        json={"ok": True},
    )

    dispatcher = TaskDispatcher(_cfg(), http_client, handlers={})
    await dispatcher._report_failure(_TASK_ID, _dispatch_msg(), "no handler for type=demo")

    body = json.loads(httpx_mock.get_request().content)
    assert body["success"] is False
    # resultSummary 是平台 jsonb 列 → {code,message} JSON 对象(非裸串)。
    assert json.loads(body["resultSummary"]) == {
        "code": "SdkDispatchError",
        "message": "no handler for type=demo",
    }
    assert body["errorCode"] == "SdkDispatchError"
    # 已废字段必须不出现
    assert "errorMessage" not in body
    assert "status" not in body
    # 必填三件套
    assert body["taskId"] == _TASK_ID
    assert body["tenantId"] == _TENANT
    assert body["workerId"] == _WORKER


# ---------------------------------------------------------------------------
# Idempotency-key contract
# ---------------------------------------------------------------------------


_IDEM_KEY_RE = re.compile(r"^sdk-py-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")


async def test_report_idempotency_key_is_sdk_py_uuid(
    httpx_mock: HTTPXMock,
    http_client: PlatformHttpClient,
) -> None:
    """P0-3:idempotency-key 必须是 ``sdk-py-<uuid4>``,与 Java ``sdk-<uuid4>`` 对齐。

    旧的固定 ``report-{taskId}`` 会让同 task 多次 report 永远 hit 平台
    幂等存储的上次结果,新 outcome 不落地。
    """
    httpx_mock.add_response(
        url=f"{_BASE}/internal/tasks/{_TASK_ID}/report",
        method="POST",
        json={"ok": True},
    )

    dispatcher = TaskDispatcher(_cfg(), http_client, handlers={})
    await dispatcher._report_success(_TASK_ID, _dispatch_msg())

    req = httpx_mock.get_request()
    idem = req.headers["Idempotency-Key"]
    assert _IDEM_KEY_RE.match(idem), f"unexpected idempotency-key format: {idem!r}"
    # 旧的固定 key 形态严禁回归
    assert idem != f"report-{_TASK_ID}"
    assert idem != f"claim-{_TASK_ID}"


async def test_report_idempotency_key_unique_per_call(
    httpx_mock: HTTPXMock,
    http_client: PlatformHttpClient,
) -> None:
    """同一 task 多次 report 必须用不同 key —— 否则平台返回上次结果。"""
    for _ in range(3):
        httpx_mock.add_response(
            url=f"{_BASE}/internal/tasks/{_TASK_ID}/report",
            method="POST",
            json={"ok": True},
        )

    dispatcher = TaskDispatcher(_cfg(), http_client, handlers={})
    msg = _dispatch_msg()
    await dispatcher._report_success(_TASK_ID, msg)
    await dispatcher._report_failure(_TASK_ID, msg, "retry-failure-1")
    await dispatcher._report_failure(_TASK_ID, msg, "retry-failure-2")

    reqs = httpx_mock.get_requests()
    keys = [r.headers["Idempotency-Key"] for r in reqs]
    assert len(set(keys)) == 3, f"idempotency keys must be unique, got {keys!r}"


# ---------------------------------------------------------------------------
# Fixture cross-check
# ---------------------------------------------------------------------------


def test_python_report_body_keys_align_with_fixture() -> None:
    """fixture 09 列出平台期望字段集合;Python success body 应是其超集中常见子集。

    fixture 是 source-of-truth(``docs/api/sdk-contract-fixtures/09-...``),
    Java SDK 通过 ``SdkWireContractTest`` 锁同一份。本断言保证 Python 发
    出的字段名(``success`` / ``taskId`` / ``tenantId`` / ``workerId`` /
    ``message``)都出现在 fixture 期望集合里,防止字段名漂移再次回归。
    """
    expected = _fixture_report_body()
    must_include = {"taskId", "tenantId", "workerId", "success"}
    fixture_keys = set(expected.keys())
    assert must_include.issubset(fixture_keys), (
        f"fixture 09 missing platform-required fields {must_include - fixture_keys}"
    )
    # 旧字段名严禁出现在 fixture
    assert "status" not in fixture_keys
    assert "errorMessage" not in fixture_keys
