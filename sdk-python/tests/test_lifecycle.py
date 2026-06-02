"""Tests for :func:`stop_with_timeout` (Lane U / P4).

The real :class:`BatchPlatformClient` lands in Lane T; we test against
a duck-typed fake that exposes the attributes documented in
``_lifecycle.py``'s module docstring.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

import pytest

from batch_worker_sdk.internal._lifecycle import stop_with_timeout


class _FakeConsumer:
    def __init__(self, stop_delay: float = 0.0) -> None:
        self.stop_called: bool = False
        self.stop_delay: float = stop_delay

    async def stop(self) -> None:
        await asyncio.sleep(self.stop_delay)
        self.stop_called = True


class _FakeDispatcher:
    def __init__(self, in_flight_sequence: list[int]) -> None:
        # Sequence consumed one entry per poll; last value sticks.
        self._sequence = list(in_flight_sequence)
        self.draining: bool = False
        self.ids: list[int] = [101, 202]

    def start_draining(self) -> None:
        self.draining = True

    def in_flight_count(self) -> int:
        if len(self._sequence) > 1:
            return self._sequence.pop(0)
        return self._sequence[0]

    def in_flight_task_ids(self) -> list[int]:
        return list(self.ids)


class _FakeScheduler:
    def __init__(self, stop_delay: float = 0.0, fail: bool = False) -> None:
        self.stop_called: bool = False
        self.stop_delay: float = stop_delay
        self.fail: bool = fail

    async def stop(self) -> None:
        await asyncio.sleep(self.stop_delay)
        if self.fail:
            raise RuntimeError("simulated scheduler stop failure")
        self.stop_called = True


class _FakeClient:
    def __init__(
        self,
        consumer: _FakeConsumer | None,
        dispatcher: _FakeDispatcher | None,
        schedulers: list[_FakeScheduler],
        deactivate_fail: bool = False,
    ) -> None:
        self.consumer = consumer
        self.dispatcher = dispatcher
        self.schedulers = schedulers
        self.deactivate_called: bool = False
        self._deactivate_fail = deactivate_fail

    async def deactivate(self) -> None:
        self.deactivate_called = True
        if self._deactivate_fail:
            raise RuntimeError("simulated /deactivate HTTP failure")


# ---------------------------------------------------------------------------
# Cases
# ---------------------------------------------------------------------------


async def test_normal_stop_completes_within_budget() -> None:
    consumer = _FakeConsumer()
    dispatcher = _FakeDispatcher(in_flight_sequence=[0])
    schedulers = [_FakeScheduler(), _FakeScheduler()]
    client = _FakeClient(consumer, dispatcher, schedulers)

    loop = asyncio.get_event_loop()
    start = loop.time()
    await stop_with_timeout(client, timeout=1.0)  # type: ignore[arg-type]
    elapsed = loop.time() - start

    assert elapsed < 0.5, f"normal stop took {elapsed:.3f}s, expected near-instant"
    assert dispatcher.draining is True
    assert consumer.stop_called is True
    assert all(s.stop_called for s in schedulers)
    assert client.deactivate_called is True


async def test_drain_waits_for_in_flight_to_reach_zero() -> None:
    consumer = _FakeConsumer()
    # Three in-flight at first poll, drops to 0 after ~150ms.
    dispatcher = _FakeDispatcher(in_flight_sequence=[3, 2, 1, 0])
    schedulers = [_FakeScheduler()]
    client = _FakeClient(consumer, dispatcher, schedulers)

    await stop_with_timeout(client, timeout=2.0)  # type: ignore[arg-type]

    assert dispatcher.draining is True
    assert consumer.stop_called is True


async def test_drain_timeout_logs_warn_with_task_ids(
    caplog: pytest.LogCaptureFixture,
) -> None:
    consumer = _FakeConsumer()
    dispatcher = _FakeDispatcher(in_flight_sequence=[2])  # never drops
    schedulers = [_FakeScheduler()]
    client = _FakeClient(consumer, dispatcher, schedulers)

    caplog.set_level(logging.WARNING, logger="batch_worker_sdk.internal._lifecycle")
    # Small budget so the test runs fast.
    await stop_with_timeout(client, timeout=0.3)  # type: ignore[arg-type]

    drain_warns = [r for r in caplog.records if "drain timed out" in r.getMessage()]
    assert drain_warns, (
        f"expected a drain-timeout WARN, got: {[r.getMessage() for r in caplog.records]}"
    )
    msg = drain_warns[0].getMessage()
    assert "101" in msg, "in-flight task id 101 must be in the warn message"
    assert "202" in msg, "in-flight task id 202 must be in the warn message"
    # All later phases still ran:
    assert all(s.stop_called for s in schedulers)
    assert client.deactivate_called is True


async def test_scheduler_failure_does_not_block_deactivate(
    caplog: pytest.LogCaptureFixture,
) -> None:
    consumer = _FakeConsumer()
    dispatcher = _FakeDispatcher(in_flight_sequence=[0])
    schedulers = [_FakeScheduler(fail=True), _FakeScheduler()]
    client = _FakeClient(consumer, dispatcher, schedulers)

    caplog.set_level(logging.WARNING, logger="batch_worker_sdk.internal._lifecycle")
    await stop_with_timeout(client, timeout=1.0)  # type: ignore[arg-type]

    assert client.deactivate_called is True
    assert schedulers[1].stop_called is True
    assert any("scheduler" in r.getMessage() for r in caplog.records)


async def test_deactivate_failure_is_swallowed(caplog: pytest.LogCaptureFixture) -> None:
    consumer = _FakeConsumer()
    dispatcher = _FakeDispatcher(in_flight_sequence=[0])
    schedulers = [_FakeScheduler()]
    client = _FakeClient(consumer, dispatcher, schedulers, deactivate_fail=True)

    caplog.set_level(logging.WARNING, logger="batch_worker_sdk.internal._lifecycle")
    # Must NOT raise — fixture 12 sdkMustNot: "block stop() if
    # /deactivate HTTP fails (log + continue exit)".
    await stop_with_timeout(client, timeout=0.5)  # type: ignore[arg-type]

    assert client.deactivate_called is True
    assert any("/deactivate" in r.getMessage() for r in caplog.records)


async def test_negative_timeout_raises() -> None:
    client: Any = _FakeClient(None, None, [])
    with pytest.raises(ValueError, match="positive"):
        await stop_with_timeout(client, timeout=-1.0)
