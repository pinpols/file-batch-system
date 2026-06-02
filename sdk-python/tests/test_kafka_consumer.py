"""Unit tests for KafkaTaskConsumer — Lane S (P2).

Uses ``MagicMock`` for the underlying ``AIOKafkaConsumer`` so we
don't need a real broker. Fixture-level pause/resume is exercised
end-to-end via the contract runner; here we cover unit branches
(missing config, subscribe path, rebalance listener cache reset).
"""

from __future__ import annotations

import asyncio
from unittest.mock import MagicMock

import pytest

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher, WorkerRuntimeState
from batch_worker_sdk._http import PlatformHttpClient
from batch_worker_sdk._kafka import KafkaTaskConsumer, _PauseAwareRebalanceListener


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


async def test_build_consumer_requires_kafka_fields() -> None:
    """Missing kafka_* config → ValueError on _build_consumer."""
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
    """in_flight >= max → pause(*assignment) exactly once, cached."""
    consumer, dispatcher, mock = await _make_consumer()
    try:

        async def _idle() -> None:
            await asyncio.sleep(60)

        for tid in range(4):
            dispatcher._in_flight[tid] = asyncio.create_task(_idle())
        try:
            consumer.apply_backpressure()
            assert mock.pause.call_count == 1
            # Second tick must not re-issue pause (caching).
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
            # Drain one slot.
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
    """Even with capacity, PAUSED/DRAINING directive should pause."""
    consumer, dispatcher, mock = await _make_consumer()
    try:
        dispatcher.apply_platform_directive({"runtimeState": "PAUSED"})
        assert dispatcher.runtime_state == WorkerRuntimeState.PAUSED
        consumer.apply_backpressure()
        assert mock.pause.call_count == 1
    finally:
        await consumer._dispatcher._http.close()


async def test_apply_backpressure_skips_when_no_assignment() -> None:
    """Empty assignment → no pause/resume RPC (avoid spurious calls)."""
    consumer, _dispatcher, mock = await _make_consumer()
    try:
        mock.assignment.return_value = set()
        consumer.apply_backpressure()
        assert mock.pause.call_count == 0
        assert mock.resume.call_count == 0
    finally:
        await consumer._dispatcher._http.close()


async def test_rebalance_listener_resets_paused_cache() -> None:
    """on_partitions_assigned → _paused flipped to False."""
    consumer, _dispatcher, _mock = await _make_consumer()
    try:
        consumer._paused = True
        listener = _PauseAwareRebalanceListener(consumer)
        await listener.on_partitions_assigned(set())
        assert consumer._paused is False
    finally:
        await consumer._dispatcher._http.close()


async def test_handle_record_dispatches_to_on_message() -> None:
    """Valid JSON → dispatcher.on_message receives parsed dict."""
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
    """Malformed JSON → ERROR log, no dispatch."""
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

        caplog.set_level("ERROR", logger="batch_worker_sdk._kafka")
        await consumer._handle_record(tp, rec)
        assert called == []
        assert any("failed to parse" in r.message for r in caplog.records)
    finally:
        await consumer._dispatcher._http.close()
