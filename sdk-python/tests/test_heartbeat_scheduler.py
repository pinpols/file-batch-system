"""Tests for ``batch_worker_sdk._scheduler.HeartbeatScheduler`` (Lane T P3).

8 cases per Lane T brief T4:

1. Normal tick → POST /heartbeat with correct body + forward parsed directive.
2. ``nextHeartbeatHint`` re-paces ``current_interval_s``.
3. Hint below floor (< 1s) is clamped up to 1s.
4. Hint above ceiling (> 10x baseline) is clamped down to 10x baseline.
5. HTTP exception is swallowed — loop survives.
6. ``apply_platform_directive`` raises → still survives.
7. ``stop()`` exits cleanly after ``start()``.
8. Double-``start()`` is idempotent.
"""

from __future__ import annotations

import asyncio
from datetime import timedelta
from typing import Any

import pytest

from batch_worker_sdk._directive import ParsedDirective
from batch_worker_sdk._scheduler import HeartbeatScheduler
from batch_worker_sdk.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import TransientError


class _FakeDispatcher:
    """Minimal :class:`DispatcherLike` stand-in for unit tests."""

    def __init__(self, in_flight: int = 0) -> None:
        self._in_flight = in_flight
        self.applied: list[Any] = []
        self.cancel_calls: list[tuple[int, str]] = []
        self.apply_raises: BaseException | None = None

    def in_flight_count(self) -> int:
        return self._in_flight

    def in_flight_task_ids(self) -> set[int]:
        return set()

    def apply_platform_directive(self, directive: Any) -> None:
        if self.apply_raises is not None:
            raise self.apply_raises
        self.applied.append(directive)

    def mark_cancel_requested(self, task_id: int, reason: str) -> None:
        self.cancel_calls.append((task_id, reason))


class _FakeHttp:
    """Records heartbeat calls; configurable response + failure."""

    def __init__(self, response: dict[str, Any] | None = None) -> None:
        self.response = response or {}
        self.calls: list[tuple[str, dict[str, Any]]] = []
        self.raise_on_next: BaseException | None = None

    async def heartbeat(self, worker_code: str, body: dict[str, Any]) -> dict[str, Any]:
        self.calls.append((worker_code, body))
        if self.raise_on_next is not None:
            err = self.raise_on_next
            self.raise_on_next = None
            raise err
        return dict(self.response)


def _cfg(**overrides: Any) -> BatchPlatformClientConfig:
    base = {
        "base_url": "http://orch:8081",
        "tenant_id": "acme",
        "worker_code": "w-1",
        "heartbeat_interval": timedelta(seconds=2),
        "lease_renew_interval": timedelta(seconds=5),
        "http_timeout": timedelta(seconds=1),
    }
    base.update(overrides)
    return BatchPlatformClientConfig(**base)


async def test_tick_posts_heartbeat_and_applies_directive() -> None:
    cfg = _cfg()
    dispatcher = _FakeDispatcher(in_flight=3)
    http = _FakeHttp({"runtimeState": "DEGRADED"})
    sched = HeartbeatScheduler(cfg, http, dispatcher)  # type: ignore[arg-type]

    directive = await sched.tick()

    assert isinstance(directive, ParsedDirective)
    assert directive.platform_status.value == "DEGRADED"
    assert len(http.calls) == 1
    worker_code, body = http.calls[0]
    assert worker_code == "w-1"
    assert body["tenantId"] == "acme"
    assert body["currentLoad"] == 3
    assert body["status"] == "RUNNING"
    assert dispatcher.applied == [directive]


async def test_next_heartbeat_hint_repaces_interval() -> None:
    cfg = _cfg(heartbeat_interval=timedelta(seconds=10))
    http = _FakeHttp({"nextHeartbeatHint": 5})
    sched = HeartbeatScheduler(cfg, http, _FakeDispatcher())  # type: ignore[arg-type]

    assert sched.current_interval_s == 10.0
    await sched.tick()
    assert sched.current_interval_s == 5.0


async def test_hint_below_floor_is_clamped_up() -> None:
    cfg = _cfg(heartbeat_interval=timedelta(seconds=10))
    http = _FakeHttp({"nextHeartbeatHint": 0.1})
    sched = HeartbeatScheduler(cfg, http, _FakeDispatcher())  # type: ignore[arg-type]

    await sched.tick()
    assert sched.current_interval_s == 1.0  # floor = MIN_HINT_S


async def test_hint_above_ceiling_is_clamped_down() -> None:
    cfg = _cfg(heartbeat_interval=timedelta(seconds=2))
    http = _FakeHttp({"nextHeartbeatHint": 999})
    sched = HeartbeatScheduler(cfg, http, _FakeDispatcher())  # type: ignore[arg-type]

    await sched.tick()
    # ceiling = 2s baseline * 10 = 20s
    assert sched.current_interval_s == 20.0


async def test_http_failure_is_swallowed(caplog: pytest.LogCaptureFixture) -> None:
    cfg = _cfg()
    http = _FakeHttp()
    http.raise_on_next = TransientError("503 Service Unavailable", status_code=503, attempts=3)
    sched = HeartbeatScheduler(cfg, http, _FakeDispatcher())  # type: ignore[arg-type]

    result = await sched.tick()

    assert result is None
    assert any("heartbeat failed" in r.message for r in caplog.records)


async def test_apply_directive_exception_does_not_kill_tick(
    caplog: pytest.LogCaptureFixture,
) -> None:
    cfg = _cfg()
    dispatcher = _FakeDispatcher()
    dispatcher.apply_raises = RuntimeError("dispatcher exploded")
    http = _FakeHttp({"runtimeState": "PAUSED"})
    sched = HeartbeatScheduler(cfg, http, dispatcher)  # type: ignore[arg-type]

    result = await sched.tick()  # must not raise

    assert result is not None
    assert any("apply_platform_directive failed" in r.message for r in caplog.records)


async def test_start_and_stop_run_cleanly() -> None:
    cfg = _cfg()  # baseline 2s, lease 5s — config-valid
    http = _FakeHttp({"runtimeState": "NORMAL"})
    sched = HeartbeatScheduler(cfg, http, _FakeDispatcher())  # type: ignore[arg-type]

    await sched.start()
    assert sched.running
    # Let the loop sleep settle; we don't wait for an actual tick to
    # keep the test fast — start() + stop() lifecycle is what's verified.
    await asyncio.sleep(0)
    await sched.stop()
    assert not sched.running


async def test_double_start_is_idempotent(caplog: pytest.LogCaptureFixture) -> None:
    cfg = _cfg()
    sched = HeartbeatScheduler(cfg, _FakeHttp(), _FakeDispatcher())  # type: ignore[arg-type]
    await sched.start()
    first_task = sched._task
    await sched.start()  # second call must not crash or replace the task
    assert sched._task is first_task
    await sched.stop()
