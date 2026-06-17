"""KafkaTaskConsumer 单元测试(P2)。

底层 ``AIOKafkaConsumer`` 用 ``MagicMock`` 替代,这样不需要真的 broker。
fixture 级 pause/resume 在 contract runner 中做端到端;这里只覆盖单元
分支(缺配置、subscribe 路径、rebalance listener 重置缓存)。
"""

from __future__ import annotations

import asyncio
from unittest.mock import MagicMock

import pytest

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher, WorkerRuntimeState
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.internal._kafka import (
    KafkaTaskConsumer,
    _jaas_field,
    _PauseAwareRebalanceListener,
)


def _cfg(**overrides: object) -> BatchPlatformClientConfig:
    base = {
        "base_url": "http://orch:8081",
        "tenant_id": "acme",
        "worker_code": "w-1",
        "max_concurrent_tasks": 4,
        "kafka_bootstrap": "kafka:9092",
        "kafka_group_id": "g-1",
        "kafka_topic_pattern": "batch.task.dispatch.acme.*",
    }
    base.update(overrides)
    return BatchPlatformClientConfig(**base)  # type: ignore[arg-type]


async def _make_consumer() -> tuple[KafkaTaskConsumer, TaskDispatcher, MagicMock]:
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    dispatcher = TaskDispatcher(cfg, http)
    mock_consumer = MagicMock()
    mock_consumer.assignment.return_value = {"part-0", "part-1"}
    consumer = KafkaTaskConsumer(cfg, dispatcher, consumer=mock_consumer)
    return consumer, dispatcher, mock_consumer


def test_jaas_field_parses_double_and_single_quotes() -> None:
    jaas = 'org.apache.kafka.common.security.scram.ScramLoginModule required username="u1" password="p1";'
    assert _jaas_field(jaas, "username") == "u1"
    assert _jaas_field(jaas, "password") == "p1"
    single = "ScramLoginModule required username='u2' password='p2';"
    assert _jaas_field(single, "username") == "u2"
    assert _jaas_field(single, "password") == "p2"
    assert _jaas_field("ScramLoginModule required;", "username") is None


async def test_resolve_sasl_credentials_prefers_explicit_then_jaas() -> None:
    http = PlatformHttpClient(_cfg())
    try:
        # 显式字段优先
        explicit = KafkaTaskConsumer(
            _cfg(kafka_sasl_username="exp-u", kafka_sasl_password="exp-p"),
            TaskDispatcher(_cfg(), http),
        )
        assert explicit._resolve_sasl_credentials() == ("exp-u", "exp-p")

        # 退化到解析 Java 风格 JAAS(对齐 Java SDK 同一份配置)
        jaas_cfg = _cfg(
            kafka_sasl_mechanism="SCRAM-SHA-512",
            kafka_sasl_jaas_config=(
                "org.apache.kafka.common.security.scram.ScramLoginModule "
                'required username="jaas-u" password="jaas-p";'
            ),
        )
        jaas = KafkaTaskConsumer(jaas_cfg, TaskDispatcher(_cfg(), http))
        assert jaas._resolve_sasl_credentials() == ("jaas-u", "jaas-p")

        # 三者皆空 → PLAINTEXT,无凭据
        plain = KafkaTaskConsumer(_cfg(), TaskDispatcher(_cfg(), http))
        assert plain._resolve_sasl_credentials() == (None, None)
    finally:
        await http.close()


async def test_build_consumer_requires_kafka_fields() -> None:
    """缺 kafka_* 配置 → _build_consumer 抛 ValueError。"""
    cfg = BatchPlatformClientConfig(
        base_url="http://orch:8081", tenant_id="acme", worker_code="w-1"
    )
    http = PlatformHttpClient(cfg)
    try:
        dispatcher = TaskDispatcher(cfg, http)
        consumer = KafkaTaskConsumer(cfg, dispatcher)
        with pytest.raises(ValueError, match="kafka_bootstrap"):
            consumer._build_consumer()
    finally:
        await http.close()


async def test_apply_backpressure_pauses_at_saturation() -> None:
    """in_flight >= max → 只 pause(*assignment) 一次,带缓存。"""
    consumer, dispatcher, mock = await _make_consumer()
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()
            assert mock.pause.call_count == 1
            # 第二次 tick 不能重发 pause(缓存)。
            consumer.apply_backpressure()
            assert mock.pause.call_count == 1
            assert consumer.paused is True
        finally:
            for t in list(dispatcher._in_flight.values()):
                t.cancel()
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_resumes_when_capacity_returns() -> None:
    consumer, dispatcher, mock = await _make_consumer()
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()  # pause
            # 排干一个槽。
            t = next(iter(dispatcher._in_flight))
            dispatcher._in_flight[t].cancel()
            dispatcher._in_flight.pop(t)
            consumer.apply_backpressure()  # resume
            assert mock.resume.call_count == 1
            assert consumer.paused is False
        finally:
            for t in list(dispatcher._in_flight.values()):
                t.cancel()
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_pauses_on_platform_paused() -> None:
    """即使还有容量,PAUSED/DRAINING directive 也应 pause。"""
    consumer, dispatcher, mock = await _make_consumer()
    try:
        dispatcher.apply_platform_directive({"runtimeState": "PAUSED"})
        assert dispatcher.runtime_state == WorkerRuntimeState.PAUSED
        consumer.apply_backpressure()
        assert mock.pause.call_count == 1
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_skips_when_no_assignment() -> None:
    """空 assignment → 不发 pause/resume RPC(避免无效调用)。"""
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        mock.assignment.return_value = set()
        consumer.apply_backpressure()
        assert mock.pause.call_count == 0
        assert mock.resume.call_count == 0
    finally:
        await consumer._dispatcher._http.close()


async def test_rebalance_listener_resets_paused_cache() -> None:
    """on_partitions_assigned → _paused 翻成 False。"""
    consumer, _dispatcher, _mock = await _make_consumer()
    try:
        consumer._paused = True
        listener = _PauseAwareRebalanceListener(consumer)
        await listener.on_partitions_assigned(set())
        assert consumer._paused is False
    finally:
        await consumer._dispatcher._http.close()


async def test_handle_record_dispatches_to_on_message() -> None:
    """合法 JSON → dispatcher.on_message 收到解析后的 dict。"""
    consumer, dispatcher, _mock = await _make_consumer()
    try:
        received: list[dict[str, object]] = []

        async def _capture(msg: dict[str, object]) -> None:
            received.append(msg)

        dispatcher.on_message = _capture  # type: ignore[method-assign]

        rec = MagicMock()
        rec.value = b'{"schemaVersion": "v2", "tenantId": "acme", "taskId": 1}'
        rec.offset = 7
        tp = MagicMock()
        tp.topic = "batch.task.dispatch.acme.echo"

        await consumer._handle_record(tp, rec)
        assert received == [{"schemaVersion": "v2", "tenantId": "acme", "taskId": 1}]
    finally:
        await consumer._dispatcher._http.close()


async def test_handle_record_skips_invalid_json(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """坏 JSON → ERROR 日志,不分发。"""
    consumer, dispatcher, _mock = await _make_consumer()
    try:
        called = []

        async def _capture(msg: dict[str, object]) -> None:
            called.append(msg)

        dispatcher.on_message = _capture  # type: ignore[method-assign]

        rec = MagicMock()
        rec.value = b"not json"
        rec.offset = 0
        tp = MagicMock()
        tp.topic = "batch.task.dispatch.acme.echo"

        caplog.set_level("ERROR", logger="batch_worker_sdk.internal._kafka")
        await consumer._handle_record(tp, rec)
        assert called == []
        assert any("failed to parse" in r.message for r in caplog.records)
    finally:
        await consumer._dispatcher._http.close()
