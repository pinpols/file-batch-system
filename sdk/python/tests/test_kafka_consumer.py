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
    """容量降到 max//2 以下 → resume(*assignment) 一次。"""
    consumer, dispatcher, mock = await _make_consumer()
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()  # pause (in_flight=4 >= max=4)
            # 排干到 in_flight < max//2(=2)才会 resume(hysteresis)。
            while dispatcher.in_flight_count() >= 2:
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


async def test_apply_backpressure_hysteresis_holds_pause_between_max_and_half() -> None:
    """#8 回归:容量在 (max//2, max) 区间内抖动时不得 resume(防边界颠簸)。

    max=4 → pause 在 in_flight>=4,resume 仅在 in_flight<2。in_flight=3/2 时
    必须保持 paused、不发 resume RPC。
    """
    consumer, dispatcher, mock = await _make_consumer()
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()  # pause at 4
            assert consumer.paused is True
            # 掉到 3:仍 >= max//2 → 不 resume。
            for target in (3, 2):
                t = next(iter(dispatcher._in_flight))
                dispatcher._in_flight[t].cancel()
                dispatcher._in_flight.pop(t)
                assert dispatcher.in_flight_count() == target
                consumer.apply_backpressure()
                assert mock.resume.call_count == 0, (
                    f"hysteresis violated: resumed at in_flight={target} (>= max//2)"
                )
                assert consumer.paused is True
            # 掉到 1:< max//2 → resume。
            t = next(iter(dispatcher._in_flight))
            dispatcher._in_flight[t].cancel()
            dispatcher._in_flight.pop(t)
            consumer.apply_backpressure()
            assert mock.resume.call_count == 1
            assert consumer.paused is False
        finally:
            for t in list(dispatcher._in_flight.values()):
                t.cancel()
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_platform_resume_not_blocked_by_hysteresis() -> None:
    """#8:平台 PAUSED→NORMAL 恢复,容量已满足 hysteresis 时应立即 resume。

    平台态本身不构成抖动源,所以平台恢复后只要 in_flight<max//2 即 resume,
    不被容量 hysteresis 拖延。
    """
    consumer, dispatcher, mock = await _make_consumer()
    try:
        dispatcher.apply_platform_directive({"runtimeState": "PAUSED"})
        consumer.apply_backpressure()  # platform pause(容量空)
        assert mock.pause.call_count == 1
        assert consumer.paused is True
        # 平台恢复 NORMAL,容量本就空(in_flight=0 < max//2)→ 立即 resume。
        dispatcher.apply_platform_directive({"runtimeState": "NORMAL"})
        consumer.apply_backpressure()
        assert mock.resume.call_count == 1
        assert consumer.paused is False
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


async def test_rebalance_listener_resets_both_pause_ledgers() -> None:
    """on_partitions_assigned → 容量账本翻 False **且** poison 单分区账本清空。"""
    consumer, _dispatcher, _mock = await _make_consumer()
    try:
        consumer._capacity_paused = True
        consumer._poison_paused.add("part-0")  # type: ignore[arg-type]
        listener = _PauseAwareRebalanceListener(consumer)
        await listener.on_partitions_assigned(set())
        assert consumer._capacity_paused is False
        assert consumer.poison_paused_partitions == frozenset()
    finally:
        await consumer._dispatcher._http.close()


async def test_capacity_resume_does_not_resume_poison_paused_partition() -> None:
    """#9 回归:poison/平台单分区 pause 与容量 pause 分开记账。

    某分区因 RETRY_LATER(poison / 未知 schema)被单独 pause 后,当容量恢复时
    apply_backpressure 的 resume 必须**跳过**该 poison 分区——只 resume 其余
    分区,否则会忙旋转重读重拒同一条坏消息。
    """
    consumer, dispatcher, mock = await _make_consumer()
    mock.assignment.return_value = {"part-0", "part-1"}
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()  # 容量 pause
            assert consumer.paused is True
            # 模拟 poll loop 因 RETRY_LATER 把 part-0 记入 poison 账本。
            consumer._poison_paused.add("part-0")  # type: ignore[arg-type]
            # 容量恢复(排干到 < max//2)。
            while dispatcher.in_flight_count() >= 2:
                t = next(iter(dispatcher._in_flight))
                dispatcher._in_flight[t].cancel()
                dispatcher._in_flight.pop(t)
            consumer.apply_backpressure()
            # 只 resume part-1,不碰 poison 的 part-0。
            assert mock.resume.call_count == 1
            resumed = set(mock.resume.call_args.args)
            assert resumed == {"part-1"}, f"poison part-0 must not be resumed, got {resumed}"
            # poison 账本不被容量 resume 清空。
            assert consumer.poison_paused_partitions == frozenset({"part-0"})
        finally:
            for t in list(dispatcher._in_flight.values()):
                t.cancel()
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
