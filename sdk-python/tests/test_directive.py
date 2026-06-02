"""Tests for ``batch_worker_sdk.scheduler._directive.parse_directive``.

5 cases per Lane T brief T4:

1. Full directive — all five fields populated round-trip into typed dataclass.
2. Missing fields — defaults (``NORMAL`` / ``False`` / ``None`` / ``[]`` / ``None``).
3. ``nextHeartbeatHint`` accepts ISO 8601 ``PT<n>S`` (Java ``Duration``).
4. Unknown ``runtimeState`` enum value → defaults to ``NORMAL`` (no raise).
5. ``directive``-nested envelope vs. flat envelope — both parse identically.
"""

from __future__ import annotations

from datetime import timedelta

import pytest

from batch_worker_sdk.scheduler._directive import ParsedDirective, parse_directive
from batch_worker_sdk.task.state import WorkerRuntimeState


def test_parse_full_directive_populates_every_field() -> None:
    raw = {
        "runtimeState": "DEGRADED",
        "shouldDrain": True,
        "desiredMaxConcurrent": 2,
        "pausedTaskTypes": ["tenant_xyz_slow_import"],
        "nextHeartbeatHint": 15,
    }

    out = parse_directive(raw)

    assert out.platform_status is WorkerRuntimeState.DEGRADED
    assert out.should_drain is True
    assert out.desired_max_concurrent == 2
    assert out.paused_task_types == ["tenant_xyz_slow_import"]
    assert out.next_heartbeat_hint == timedelta(seconds=15)


def test_parse_empty_body_returns_all_defaults() -> None:
    out = parse_directive({})

    assert out == ParsedDirective(raw={})
    assert out.platform_status is WorkerRuntimeState.NORMAL
    assert out.should_drain is False
    assert out.desired_max_concurrent is None
    assert out.paused_task_types == []
    assert out.next_heartbeat_hint is None


def test_iso8601_duration_hint_parses_seconds_and_minutes() -> None:
    sec = parse_directive({"nextHeartbeatHint": "PT5S"})
    minute = parse_directive({"nextHeartbeatHint": "PT2M"})

    assert sec.next_heartbeat_hint == timedelta(seconds=5)
    assert minute.next_heartbeat_hint == timedelta(minutes=2)


def test_unknown_runtime_state_defaults_normal(caplog: pytest.LogCaptureFixture) -> None:
    out = parse_directive({"runtimeState": "EXPLODING"})

    assert out.platform_status is WorkerRuntimeState.NORMAL
    assert any("unknown runtimeState" in r.message for r in caplog.records)


def test_nested_directive_envelope_matches_flat() -> None:
    flat = parse_directive({"runtimeState": "PAUSED"})
    nested = parse_directive({"directive": {"runtimeState": "PAUSED"}})

    assert nested.platform_status is WorkerRuntimeState.PAUSED
    assert flat.platform_status is WorkerRuntimeState.PAUSED
    # ``raw`` should reflect the inner payload, not the wrapping envelope
    assert nested.raw == {"runtimeState": "PAUSED"}
