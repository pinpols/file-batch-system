"""contract fixture 运行器。

发现 ``docs/api/sdk-contract-fixtures/`` 下发布的所有 JSON fixture,
并用 SDK 跑一遍。

阶段分工
--------
P1 实现纯 HTTP 层行为:register / heartbeat / claim / report / renew
端到端走 ``PlatformHttpClient`` 打到 mock 平台。每个 fixture 的
``when.responseStatus`` + ``responseBody`` 被加载进 ``pytest_httpx``,
对应的 SDK 方法被调用,断言文档化的分类(成功 / AuthError /
409 幂等成功 / 5xx 重试 + TransientError)。

P1 **不** 驱动 Kafka 通道的 fixture,也不驱动那些需要 FSM / scheduler
做额外副作用(例如"drain 并 deactivate 后退出")的 fixture。
这些会保持 ``xfail``,由下列阶段认领:

- ~~P2(Kafka consumer):11-kafka-partition-pause-on-capacity~~
  → **已落 P2**,通过下面的 ``apply_backpressure`` 分支验证。
- P4(FSM stop/drain):12-stop-with-timeout

03-06 的 directive 生效那侧(例如 "FSM 切到 PAUSED")在 P3-P4 落地;
这里只验证 HTTP 请求/响应形状 —— 这是大部分 BYO SDK 实现者需要直接
照抄的部分。
"""

from __future__ import annotations

import asyncio
import json
from datetime import timedelta
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock

import pytest
from pytest_httpx import HTTPXMock

from batch_worker_sdk import (
    AuthError,
    BatchPlatformClientConfig,
)
from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher
from batch_worker_sdk.exceptions import TransientError
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.internal._kafka import KafkaTaskConsumer

# <repo>/sdk-python/tests/contract/test_contract_runner.py -> <repo>
_REPO_ROOT = Path(__file__).resolve().parents[4]
_FIXTURES_DIR = _REPO_ROOT / "docs" / "api" / "sdk-contract-fixtures"

# ---------------------------------------------------------------------------
# 阶段归属表 —— 与模块 docstring 保持同步。
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
    # 12-stop-with-timeout:这里只能验证 /deactivate 这次 HTTP 请求;
    # FSM 排干时序由 P4 落地。
    "12-stop-with-timeout",
}

# P2 —— Kafka 通道:dispatcher + KafkaTaskConsumer 直接断言 backpressure
# 副作用(无 HTTP wire)。
_P2_KAFKA_FIXTURES: set[str] = {
    "11-kafka-partition-pause-on-capacity",
}

# 其它仍 pending 的(FSM stop/drain 语义)。
_DEFERRED_FIXTURES: set[str] = set()

# 请求侧断言 lane(requestBodyIncludes / requestBodyExcludes / requestHeaders /
# schemaAccept)在 Java 静态 + Java wire 测 + TS/Go/Rust 决策核里硬断言;Python
# 侧是软门(xfail),这批 fixture 的请求侧构造留作 Python 后续增量(skip,不静默
# 通过)。详见 docs/sdk/byo-conformance-contract.md §2 请求侧字段。
_REQUEST_SIDE_FIXTURES: set[str] = {
    "13-report-field-names-redline",
    "14-partition-invocation-id-passthrough",
    "15-partition-invocation-id-absent-when-unclaimed",
    "16-kafka-schema-version-missing-accept",
    "17-kafka-schema-version-v2-accept",
    "18-kafka-schema-version-v3-reject",
    "19-register-apikey-in-header-not-body",
    "20-report-idempotency-key-header",
    "21-claim-4xx-client-error-no-failfast",
    "22-renew-404-not-found-give-up",
}


def _discover_fixtures() -> list[Path]:
    """返回所有 contract fixture,跳过同目录下的 drift-guard metadata
    文件(``fixture-schema.json`` 等)—— 它们是 JSON Schema,不是
    fixture 信封。"""
    if not _FIXTURES_DIR.is_dir():
        return []
    skip = {"fixture-schema"}
    return sorted(p for p in _FIXTURES_DIR.glob("*.json") if p.is_file() and p.stem not in skip)


_FIXTURES = _discover_fixtures()
_FIXTURE_IDS = [p.stem for p in _FIXTURES]


def _load_payloads() -> dict[str, dict[str, Any]]:
    """import 时一次性加载所有 fixture。

    避免 ASYNC240(async 测试体里不要做同步 I/O),也让测试体保持
    专注于断言。
    """
    return {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in _FIXTURES}


_PAYLOADS = _load_payloads()


def _cfg_from_fixture(fixture_cfg: dict[str, Any]) -> BatchPlatformClientConfig:
    """从 fixture 的 ``given.config`` 块构造合法的 SDK config。

    fixture 故意省略与场景无关的字段,这里补上安全默认值。
    重试延迟固定为 1ms,让 5xx-retry fixture 跑得快。
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
    """把 fixture 的 HTTP 调用派发给匹配的 SDK 方法。"""
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
    """跑一个 contract fixture 走完 SDK,验证 HTTP 行为。

    Deferred 的 fixture(Kafka / FSM-stop)按本文件顶部的阶段分工表
    被 ``xfail`` 跳过。
    """
    fixture_id = fixture_path.stem
    if fixture_id in _REQUEST_SIDE_FIXTURES:
        pytest.skip(
            "请求侧断言 lane(requestBody*/requestHeaders/schemaAccept):Python 软门后续增量,"
            "硬断言已在 Java(静态+wire)+ TS/Go/Rust 决策核覆盖"
        )
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

    if fixture_id in _P2_KAFKA_FIXTURES:
        await _assert_kafka_fixture(given, when, fixture_id)
        return

    if when.get("channel") != "http":
        # 将来出现没有 P2 主的非 http 通道 fixture 时,直接显式失败,
        # 别静默通过。
        pytest.fail(f"non-http channel fixture: {fixture_id} channel={when.get('channel')!r}")

    cfg = _cfg_from_fixture(given.get("config") or {})
    status = when["responseStatus"]
    body = when.get("responseBody")

    # 5xx-retry fixture 需要同一个 5xx 返回 `retry_max_attempts` 次
    # (SDK 会重试这么多次)。
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
        # 幂等成功:返回响应体,不抛错
        assert isinstance(result, dict)
        if body and "code" in body:
            assert result.get("code") == body["code"]
        return

    if 200 <= status < 300:
        if body is None or result is None:
            return  # deactivate / 空 body
        for k, v in body.items():
            assert result.get(k) == v, f"field {k} mismatch in {fixture_id}"


async def _assert_kafka_fixture(
    given: dict[str, Any],
    when: dict[str, Any],
    fixture_id: str,
) -> None:
    """P2:驱动 ``KafkaTaskConsumer.apply_backpressure()``,断言
    fixture 11 的 pause/resume 语义。

    fixture 的 ``given.state`` 描述了 dispatcher 的 in-flight 数量
    与已分配分区;``then.sdkExpectedAction`` 要求饱和时 pause、in-flight
    降到 max 以下时 resume。这里用 ``MagicMock`` AIOKafkaConsumer
    避免动真 broker。
    """
    cfg_block = dict(given.get("config") or {})
    state = given.get("state") or {}
    max_in_flight = int(cfg_block.get("maxConcurrentTasks", 4))

    cfg = BatchPlatformClientConfig(
        base_url="http://orch:8081",
        tenant_id=str(cfg_block.get("tenantId", "acme")),
        worker_code=str(cfg_block.get("workerCode", "w-1")),
        max_concurrent_tasks=max_in_flight,
        kafka_bootstrap="kafka:9092",
        kafka_group_id="g-1",
        kafka_topic_pattern=f"batch.task.dispatch.{cfg_block.get('tenantId', 'acme')}.*",
    )
    http = PlatformHttpClient(cfg)
    try:
        dispatcher = TaskDispatcher(cfg, http)

        # 塞一批假 in-flight 任务把饱和阈值打满。
        async def _idle() -> None:
            await asyncio.sleep(3600)

        for i in range(int(state.get("inFlight", max_in_flight))):
            tid = 90000 + i
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            mock_consumer = MagicMock()
            mock_assignment = {
                # aiokafka 用 set of TopicPartition 暴露 assignment;
                # 这里只需要 truthy + 非空。
                f"part-{p}": True
                for p in state.get("assignedPartitions") or ["t-0"]
            }
            mock_consumer.assignment.return_value = mock_assignment

            consumer = KafkaTaskConsumer(cfg, dispatcher, consumer=mock_consumer)
            # 饱和 → pause。
            consumer.apply_backpressure()
            assert mock_consumer.pause.call_count == 1, (
                f"{fixture_id}: expected pause(*assignment) once at saturation"
            )
            assert mock_consumer.resume.call_count == 0
            assert consumer.paused is True

            # 掉一个 in-flight → 期望 resume。
            done_tid = next(iter(dispatcher._in_flight))
            dispatcher._in_flight[done_tid].cancel()
            dispatcher._in_flight.pop(done_tid)

            consumer.apply_backpressure()
            assert mock_consumer.resume.call_count == 1, (
                f"{fixture_id}: expected resume(*assignment) when in-flight drops"
            )
            assert consumer.paused is False
        finally:
            for t in list(dispatcher._in_flight.values()):
                t.cancel()
    finally:
        await http.close()


def test_fixture_discovery_reports_count(capsys: pytest.CaptureFixture[str]) -> None:
    """诊断测试 —— 打印 fixture 清单和阶段拆分。"""
    count = len(_FIXTURES)
    p1 = sorted(f for f in _FIXTURE_IDS if f in _P1_HTTP_FIXTURES)
    deferred = sorted(f for f in _FIXTURE_IDS if f in _DEFERRED_FIXTURES)
    p2 = sorted(f for f in _FIXTURE_IDS if f in _P2_KAFKA_FIXTURES)
    other = sorted(
        f
        for f in _FIXTURE_IDS
        if f not in _P1_HTTP_FIXTURES
        and f not in _DEFERRED_FIXTURES
        and f not in _P2_KAFKA_FIXTURES
        and f not in _REQUEST_SIDE_FIXTURES
    )
    if count == 0:
        print(f"[contract] 0 fixtures discovered at {_FIXTURES_DIR}")
    else:
        print(
            f"[contract] {count} fixtures discovered; "
            f"P1-implemented={len(p1)}, P2-implemented={len(p2)}, "
            f"deferred={len(deferred)}, other={len(other)}"
        )
        print(f"[contract] P1: {p1}")
        print(f"[contract] P2: {p2}")
        print(f"[contract] deferred: {deferred}")
        if other:
            print(f"[contract] other: {other}")
    assert count >= 0
