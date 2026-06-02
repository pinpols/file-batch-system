"""Tests for ``batch_worker_sdk.client.BatchPlatformClient`` (Lane T P3).

5 cases per Lane T brief T4:

1. ``register_handler`` rejects duplicate task types.
2. ``start()`` with no handlers raises ``RuntimeError``.
3. ``start()`` order: register HTTP → schedulers up → kafka up.
4. Double-``start()`` raises ``RuntimeError``.
5. ``stop()`` (phase-3 fallback) runs the right shutdown sequence.
"""

from __future__ import annotations

from datetime import timedelta
from typing import Any
from unittest.mock import AsyncMock

import pytest

from batch_worker_sdk import BatchPlatformClient, BatchPlatformClientConfig
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult


class _StubHandler:
    """Minimal handler with configurable task_type."""

    def __init__(self, type_: str) -> None:
        self._type = type_

    def task_type(self) -> str:
        return self._type

    async def execute(self, ctx: Any) -> SdkTaskResult:  # pragma: no cover - unused
        return SdkTaskResult.success()

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        return None

    def cancel(self, ctx: Any) -> None:  # pragma: no cover - unused
        return None


class _RecordingDispatcher:
    """DispatcherLike with shutdown hook used by stop()."""

    def __init__(self) -> None:
        self.shutdown_calls: list[float] = []

    def in_flight_count(self) -> int:
        return 0

    def in_flight_task_ids(self) -> set[int]:
        return set()

    def apply_platform_directive(self, directive: Any) -> None:
        return None

    def mark_cancel_requested(self, task_id: int, reason: str) -> None:
        return None

    async def shutdown(self, timeout: float) -> None:  # noqa: ASYNC109 — mirror Lane S API
        self.shutdown_calls.append(timeout)


class _RecordingKafka:
    def __init__(self) -> None:
        self.started = False
        self.stopped = False

    async def start(self) -> None:
        self.started = True

    async def stop(self) -> None:
        self.stopped = True


def _cfg() -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url="http://orch:8081",
        tenant_id="acme",
        worker_code="w-1",
        heartbeat_interval=timedelta(seconds=2),
        lease_renew_interval=timedelta(seconds=5),
        http_timeout=timedelta(seconds=1),
    )


def _http_mock() -> AsyncMock:
    """Build an ``AsyncMock`` matching the :class:`PlatformHttpClient` shape."""
    http = AsyncMock()
    http.register = AsyncMock(return_value={})
    http.heartbeat = AsyncMock(return_value={})
    http.deactivate = AsyncMock(return_value=None)
    http.renew = AsyncMock(return_value={})
    http.close = AsyncMock(return_value=None)
    return http


def test_register_handler_rejects_duplicate() -> None:
    client = BatchPlatformClient(_cfg(), http=_http_mock())
    client.register_handler(_StubHandler("import"))

    with pytest.raises(ValueError, match="duplicate handler"):
        client.register_handler(_StubHandler("import"))


async def test_start_without_handlers_raises() -> None:
    client = BatchPlatformClient(_cfg(), http=_http_mock())

    with pytest.raises(RuntimeError, match="at least one SdkTaskHandler"):
        await client.start()


async def test_start_sequences_register_then_schedulers_then_kafka() -> None:
    http = _http_mock()
    dispatcher = _RecordingDispatcher()
    kafka = _RecordingKafka()
    events: list[str] = []

    # Wrap register so we can record when it's called relative to kafka.start
    original_register = http.register

    async def register_recorder(*args: Any, **kwargs: Any) -> dict[str, Any]:
        events.append("register")
        return await original_register(*args, **kwargs)

    http.register = register_recorder

    async def kafka_start_recorder() -> None:
        events.append("kafka.start")

    kafka.start = kafka_start_recorder  # type: ignore[method-assign]

    client = BatchPlatformClient(
        _cfg(),
        http=http,
        dispatcher_factory=lambda c, h, hs: dispatcher,
        kafka_factory=lambda c, d: kafka,
    )
    client.register_handler(_StubHandler("import"))

    await client.start()
    try:
        assert client.started is True
        # register first, kafka.start last
        assert events[0] == "register"
        assert events[-1] == "kafka.start"
        assert client.dispatcher is dispatcher
    finally:
        # Cleanly tear down the heartbeat / lease background tasks.
        await client.stop(timeout=1.0)


async def test_double_start_raises() -> None:
    http = _http_mock()
    client = BatchPlatformClient(
        _cfg(),
        http=http,
        dispatcher_factory=lambda c, h, hs: _RecordingDispatcher(),
        kafka_factory=lambda c, d: _RecordingKafka(),
    )
    client.register_handler(_StubHandler("import"))

    await client.start()
    try:
        with pytest.raises(RuntimeError, match="already started"):
            await client.start()
    finally:
        await client.stop(timeout=1.0)


async def test_stop_phase3_fallback_runs_full_sequence() -> None:
    http = _http_mock()
    dispatcher = _RecordingDispatcher()
    kafka = _RecordingKafka()
    client = BatchPlatformClient(
        _cfg(),
        http=http,
        dispatcher_factory=lambda c, h, hs: dispatcher,
        kafka_factory=lambda c, d: kafka,
    )
    client.register_handler(_StubHandler("import"))
    await client.start()

    await client.stop(timeout=5.0)

    assert client.started is False
    assert kafka.stopped is True
    assert len(dispatcher.shutdown_calls) == 1
    # deactivate must have been attempted
    http.deactivate.assert_awaited_once()
