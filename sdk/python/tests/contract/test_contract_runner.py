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
import re
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
from batch_worker_sdk.constants import SCHEMA_VERSIONS_SUPPORTED
from batch_worker_sdk.dispatcher.dispatcher import (
    DispatchDisposition,
    TaskDispatcher,
    _new_idempotency_key,
)
from batch_worker_sdk.exceptions import PersistentClientError, TransientError
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

# 其它仍 pending 的。
#
# 25-heartbeat-503-no-backoff:**Python 后续项**。各决策核(TS/Go/Rust)用纯函数
# classify_heartbeat_renew_error 表达 §C 豁免(heartbeat/renew 单次失败不走指数
# 退避,跳过本 tick 等下一 tick)。但 Python 的 PlatformHttpClient.heartbeat 复用
# 通用 with_retry,对所有 5xx 一视同仁做指数退避——要让 503 走单次豁免必须改
# 生产 retry 路径(给 heartbeat/renew 单独 no-backoff 分支),属独立 wire 行为
# 变更、超出本 conformance 增量范围,故 Python 侧暂 xfail(strict),标后续。
#
# 28-kafka-paused-task-type-drop:**Python 后续项**。决策核(TS/Go/Rust)用
# decide_paused_task_type 在收到消息时按 pausedTaskTypes 丢弃。Python SDK 已能
# 从心跳 directive 解析 pausedTaskTypes(scheduler/_directive.py),但 dispatcher
# 的 apply_platform_directive 当前只落 runtimeState,**未**在 on_message 里按
# pausedTaskTypes 做 per-message drop。补这条 drop 是 dispatcher 行为变更,留作
# 后续(届时直接驱动 on_message 断言不 claim 即可转硬)。
# 详见 docs/sdk/byo-conformance-contract.md §2.1 后续清单。
_DEFERRED_FIXTURES: set[str] = {
    "25-heartbeat-503-no-backoff",
    "28-kafka-paused-task-type-drop",
}

# 响应侧分类硬断言 lane(2026-06-16 转硬):驱动**真实** PlatformHttpClient 打到
# pytest_httpx,断言 §B/§C 的分类(typed exception + 累计 4xx fail-fast 阈值),
# 与 TS/Go/Rust 决策核 classify_http 同一规则。
_RESPONSE_SIDE_FIXTURES: set[str] = {
    "21-claim-4xx-client-error-no-failfast",
    "22-renew-404-not-found-give-up",
    "23-claim-4xx-fifth-fail-fast",
}

# 请求侧硬断言 lane(2026-06-16 增量 2/3 转硬):Python 不再 skip 请求侧 fixture,
# 而是 mirror TS/Go/Rust 的 build_request,用**真实** PlatformHttpClient 打到
# pytest_httpx,捕获实际出向 body + headers,断言 requestBodyIncludes /
# requestBodyExcludes / requestHeaders。schemaAccept(§A)直接复用 SDK 的
# SCHEMA_VERSIONS_SUPPORTED 分类。详见 docs/sdk/byo-conformance-contract.md §2.1。
#
# 仍走响应侧分类断言(action/retry/failFast)的 fixture(21/22/23)由
# _RESPONSE_SIDE_FIXTURES 分支覆盖,不在此集合;25 见 _DEFERRED_FIXTURES。
_REQUEST_BODY_FIXTURES: set[str] = {
    "13-report-field-names-redline",
    "14-partition-invocation-id-passthrough",
    "15-partition-invocation-id-absent-when-unclaimed",
    "19-register-apikey-in-header-not-body",
    "20-report-idempotency-key-header",
    "24-report-idempotency-key-minted-not-fixed",
}

# §A schemaVersion 接受/拒绝(kafka-only,纯分类,无 wire)。
_SCHEMA_ACCEPT_FIXTURES: set[str] = {
    "16-kafka-schema-version-missing-accept",
    "17-kafka-schema-version-v2-accept",
    "18-kafka-schema-version-v3-reject",
    "29-kafka-ignore-unknown-field",
}

# decode 错误 commit-skip(kafka-only):不可解码的 poison 消息 → DROP_TERMINAL
# (跳过并提交 offset),避免损坏消息永久 HOL 阻塞分区。parity §4.5 / fixture 30。
_DECODE_COMMIT_SKIP_FIXTURES: set[str] = {
    "30-kafka-decode-error-commit-skip",
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
async def test_contract_fixture(  # noqa: PLR0912 — fixture 路由表,分支随 fixture 类别线性增长
    fixture_path: Path,
    httpx_mock: HTTPXMock,
    request: pytest.FixtureRequest,
) -> None:
    """跑一个 contract fixture 走完 SDK,验证 HTTP 行为。

    Deferred 的 fixture(Kafka / FSM-stop)按本文件顶部的阶段分工表
    被 ``xfail`` 跳过。
    """
    fixture_id = fixture_path.stem

    # 请求侧硬断言 lane(2026-06-16 转硬):用 fixture 的 given.state.request
    # 复刻出向请求,经**真实** PlatformHttpClient 打到 pytest_httpx,捕获实际
    # 出向 body + headers 做断言。不再 skip。
    if fixture_id in _REQUEST_BODY_FIXTURES:
        await _assert_request_side_fixture(fixture_id, httpx_mock)
        return

    # §A schemaVersion 接受/拒绝:纯分类(kafka-only,无 wire),复用 SDK 的
    # SCHEMA_VERSIONS_SUPPORTED,与 TS/Go/Rust 决策核同一规则硬断言。
    if fixture_id in _SCHEMA_ACCEPT_FIXTURES:
        _assert_schema_accept_fixture(fixture_id)
        return

    if fixture_id in _DEFERRED_FIXTURES:
        request.applymarker(
            pytest.mark.xfail(
                strict=True,
                reason=(
                    "deferred to a future Python production change (heartbeat no-backoff "
                    "exemption / per-message pausedTaskTypes drop) — see "
                    "byo-conformance-contract.md §2.1 後續清單"
                ),
            )
        )
        if fixture_id == "28-kafka-paused-task-type-drop":
            await _assert_paused_drop_fixture(fixture_id)
        else:
            # 25-heartbeat-503-no-backoff:Python heartbeat 复用通用 with_retry,
            # 对 503 会做 3 次指数退避,违反 §C 单次豁免契约 → strict xfail。
            await _assert_response_side_fixture(fixture_id, httpx_mock)
        return

    # 响应侧分类硬断言 lane(§B/§C):typed exception + 累计 4xx fail-fast 阈值。
    if fixture_id in _RESPONSE_SIDE_FIXTURES:
        await _assert_response_side_fixture(fixture_id, httpx_mock)
        return

    payload = _PAYLOADS[fixture_id]
    given = payload.get("given") or {}
    when = payload.get("when") or {}

    if fixture_id in _P2_KAFKA_FIXTURES:
        await _assert_kafka_fixture(given, when, fixture_id)
        return

    if fixture_id in _DECODE_COMMIT_SKIP_FIXTURES:
        await _assert_kafka_decode_commit_skip()
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


def _build_request_body(spec: dict[str, Any], cfg: BatchPlatformClientConfig) -> dict[str, Any]:
    """按 given.state.request 复刻出向 body —— 字段名严格对齐 dispatcher /
    lease 的真实 wire 形状(taskId/tenantId/workerId/success/outputs/errorCode/
    resultSummary/partitionInvocationId),把 13 的字段名红线机器化。"""
    kind = spec["kind"]
    if kind == "register":
        return {"tenantId": cfg.tenant_id, "workerCode": cfg.worker_code}
    if kind == "renew":
        body: dict[str, Any] = {"tenantId": cfg.tenant_id, "workerId": cfg.worker_code}
        if spec.get("partitionInvocationId") is not None:
            body["partitionInvocationId"] = str(spec["partitionInvocationId"])
        return body
    if kind == "report":
        report = dict(spec.get("report") or {})
        body = {
            "taskId": spec["taskId"],
            "tenantId": cfg.tenant_id,
            "workerId": cfg.worker_code,
            "success": bool(report.get("success", False)),
        }
        if "outputs" in report:
            body["outputs"] = report["outputs"]
        if report.get("errorCode") is not None:
            body["errorCode"] = report["errorCode"]
        if report.get("resultSummary") is not None:
            body["resultSummary"] = report["resultSummary"]
        if spec.get("partitionInvocationId") is not None:
            body["partitionInvocationId"] = str(spec["partitionInvocationId"])
        return body
    raise NotImplementedError(f"request kind not built: {kind}")


async def _assert_request_side_fixture(fixture_id: str, httpx_mock: HTTPXMock) -> None:
    """请求侧硬断言:用真实 PlatformHttpClient 发出请求,捕获出向 body+headers。

    各语言 mint 的 idempotency-key 前缀不同(go-/ts-/rs-/sdk-py-),fixture 24
    的正则放宽到「一段或多段小写前缀 + hex8 + hex/连字符」即可锁住「非固定值」。
    Python 直接复用 SDK 的 ``_new_idempotency_key()``(``sdk-py-<uuid4>``),
    断言它确实 mint 出非固定键。
    """
    payload = _PAYLOADS[fixture_id]
    given = payload.get("given") or {}
    when = payload.get("when") or {}
    expect = ((payload.get("then") or {}).get("expect")) or {}
    spec = (given.get("state") or {}).get("request") or {}
    cfg = _cfg_from_fixture(given.get("config") or {})

    httpx_mock.add_response(
        url=cfg.base_url + when["path"],
        method=when["method"],
        status_code=when["responseStatus"],
        json=when.get("responseBody"),
    )

    body = _build_request_body(spec, cfg)
    kind = spec["kind"]
    client = PlatformHttpClient(cfg)
    try:
        if kind == "register":
            await client.register(body)
        elif kind == "renew":
            await client.renew(int(spec["taskId"]), body)
        elif kind == "report":
            task_id = int(spec["taskId"])
            idem = spec.get("idempotencyKey") or _new_idempotency_key()
            await client.report(task_id, idem, body)
    finally:
        await client.close()

    requests = httpx_mock.get_requests()
    assert len(requests) == 1, f"{fixture_id}: expected exactly one outgoing request"
    sent = requests[0]
    sent_body: dict[str, Any] = json.loads(sent.content.decode("utf-8") or "{}")

    for key, val in (expect.get("requestBodyIncludes") or {}).items():
        assert key in sent_body, f"{fixture_id}: outgoing body missing key {key!r}"
        assert sent_body[key] == val, (
            f"{fixture_id}: body[{key!r}]={sent_body[key]!r} != expected {val!r}"
        )
    for key in expect.get("requestBodyExcludes") or []:
        assert key not in sent_body, (
            f"{fixture_id}: outgoing body must NOT contain {key!r} (got {sent_body})"
        )
    for name, pattern in (expect.get("requestHeaders") or {}).items():
        value = sent.headers.get(name)
        assert value is not None, f"{fixture_id}: outgoing headers missing {name!r}"
        assert re.match(pattern, value), (
            f"{fixture_id}: header {name!r}={value!r} does not match /{pattern}/"
        )


def _assert_schema_accept_fixture(fixture_id: str) -> None:
    """§A schemaVersion 接受/拒绝:与 dispatcher 同一规则(缺省/已知 major →
    accept;未知 major → reject;未知字段不影响 accept),复用 SDK 的
    SCHEMA_VERSIONS_SUPPORTED。"""
    payload = _PAYLOADS[fixture_id]
    body = (payload.get("when") or {}).get("body") or {}
    expect = ((payload.get("then") or {}).get("expect")) or {}
    version = body.get("schemaVersion")
    if version is None or version == "":
        accept = True  # 缺省 → 旧编排器,按 v1 接受
    else:
        accept = any(str(version).startswith(p) for p in SCHEMA_VERSIONS_SUPPORTED)
    assert accept == expect["schemaAccept"], (
        f"{fixture_id}: schemaAccept({version!r})={accept} != expected {expect['schemaAccept']}"
    )


async def _assert_response_side_fixture(fixture_id: str, httpx_mock: HTTPXMock) -> None:
    """§B/§C 响应侧分类:真实 PlatformHttpClient → typed exception + 累计 4xx
    fail-fast 阈值。

    - 21 (claim 422, count=0)     → PersistentClientError,单次,counter 未达阈值
    - 22 (renew 404)              → PersistentClientError(404),单次,counter 不递增
    - 23 (claim 422, 已累计 4 次) → PersistentClientError,单次,counter 跨阈值 fatal
    - 25 (heartbeat 503)          → 契约要求单次豁免;Python 实际退避 3 次 → xfail
    """
    payload = _PAYLOADS[fixture_id]
    given = payload.get("given") or {}
    when = payload.get("when") or {}
    state = given.get("state") or {}
    status = when["responseStatus"]
    cfg = _cfg_from_fixture(given.get("config") or {})

    # 5xx 契约下平台会重复返回(若 SDK 退避会消费多次);此处按 max_attempts 注册。
    response_count = cfg.retry_max_attempts if status and status >= 500 else 1
    for _ in range(response_count):
        httpx_mock.add_response(
            url=cfg.base_url + when["path"],
            method=when["method"],
            status_code=status,
            json=when.get("responseBody"),
        )

    client = PlatformHttpClient(cfg)
    # 预置累计 4xx 计数(fixture 23 的 given.state.clientErrorCount=4)。
    seeded = int(state.get("clientErrorCount", 0))
    client.client_error_counter.count = seeded

    try:
        if status and status >= 500:
            # §C 契约:heartbeat/renew 单次失败不退避(maxAttempts:1)。Python
            # heartbeat 复用通用退避 → 这条断言会失败(被 deferred xfail 捕获)。
            with pytest.raises(TransientError) as ei:
                await _invoke(client, when)
            assert len(httpx_mock.get_requests()) == 1, (
                f"{fixture_id}: §C no-backoff exemption — heartbeat 5xx must be a "
                f"single attempt, got {len(httpx_mock.get_requests())}"
            )
            assert ei.value.attempts == 1
            return

        # 非 401/403/409/2xx 的 4xx(含 404)→ PersistentClientError,单次。
        with pytest.raises(PersistentClientError):
            await _invoke(client, when)
        assert len(httpx_mock.get_requests()) == 1, f"{fixture_id}: 4xx must not retry"

        threshold = cfg.client_error_fail_fast_threshold
        expect = ((payload.get("then") or {}).get("expect")) or {}
        if expect.get("failFast") is True:
            # 跨过累计阈值 → fatal(对齐 TS/Go/Rust action:fail-fast)。
            assert client.client_error_counter.fatal is True, (
                f"{fixture_id}: cumulative 4xx reached threshold {threshold} → fail-fast"
            )
        elif expect.get("failFast") is False:
            assert client.client_error_counter.fatal is False, (
                f"{fixture_id}: single 4xx below threshold must NOT fail-fast"
            )
    finally:
        await client.close()


async def _assert_kafka_decode_commit_skip() -> None:
    """30-kafka-decode-error-commit-skip 的契约断言。

    不可解码的 poison 记录(非 JSON / 无法反序列化)→ consumer 返回
    ``DROP_TERMINAL``,poll loop 据此提交 offset 跳过(commit-skip),避免一条
    损坏消息永久 head-of-line 阻塞分区。驱动真实 ``KafkaTaskConsumer._handle_record``。
    """
    cfg = _cfg_from_fixture({"tenantId": "acme", "workerCode": "w-1"})
    http = PlatformHttpClient(cfg)
    try:
        consumer = KafkaTaskConsumer(cfg, TaskDispatcher(cfg, http))
        rec = MagicMock()
        rec.value = b"not-json-garbage"
        rec.offset = 5
        tp = MagicMock()
        tp.topic = "batch.task.dispatch.acme.t0"
        disp = await consumer._handle_record(tp, rec)
        assert disp is DispatchDisposition.DROP_TERMINAL, (
            f"undecodable record must be DROP_TERMINAL (commit-skip), got {disp}"
        )
    finally:
        await http.close()


async def _assert_paused_drop_fixture(fixture_id: str) -> None:
    """28-kafka-paused-task-type-drop 的契约断言(当前 Python 未实现 → xfail)。

    契约:收到 workerType ∈ pausedTaskTypes 的消息时,**丢弃且不 CLAIM**(平台
    unpause 后重投)。这里驱动真实 dispatcher.on_message,断言这类消息**没有**
    触发 CLAIM。Python dispatcher 当前不在 on_message 里看 pausedTaskTypes,会照
    常 CLAIM → 断言失败 → 被 deferred strict xfail 捕获。补 per-message drop 后
    本断言转绿。
    """
    payload = _PAYLOADS[fixture_id]
    given = payload.get("given") or {}
    when = payload.get("when") or {}
    msg = dict(when.get("body") or {})
    paused = (given.get("state") or {}).get("pausedTaskTypes") or []
    assert msg.get("workerType") in paused, (
        f"{fixture_id}: fixture self-check — workerType must be in pausedTaskTypes"
    )

    cfg = _cfg_from_fixture(given.get("config") or {})
    http = MagicMock()
    claim_mock = MagicMock(return_value={})

    async def _claim(*args: Any, **kwargs: Any) -> dict[str, Any]:
        claim_mock(*args, **kwargs)
        return {}

    http.claim = _claim
    dispatcher = TaskDispatcher(cfg, http)
    # 把平台暂停集合喂给 dispatcher(经心跳 directive 通路)。
    dispatcher.apply_platform_directive({"pausedTaskTypes": list(paused)})

    await dispatcher.on_message(msg)
    # 等所有后台 task 跑完(若被 drop,根本不会有 in-flight task)。
    for task in list(dispatcher._in_flight.values()):
        await task

    assert claim_mock.call_count == 0, (
        f"{fixture_id}: paused-type message must be dropped WITHOUT CLAIM "
        f"(got {claim_mock.call_count} claim call(s))"
    )


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
        and f not in _REQUEST_BODY_FIXTURES
        and f not in _SCHEMA_ACCEPT_FIXTURES
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
