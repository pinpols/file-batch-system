"""``HeartbeatScheduler._apply_hint`` 钳制边界参数化测试(Lane D / TOP #7 Py)。

补强既有 ``test_heartbeat_scheduler.py`` 中下/上限两条用例:以参数化矩阵
锁定 ``[MIN_HEARTBEAT_INTERVAL_S, baseline * MAX_HEARTBEAT_HINT_MULTIPLIER]``
契约,避免常量被误改后心跳风暴或饥饿回归。
"""

from __future__ import annotations

from datetime import timedelta
from typing import Any

import pytest

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.scheduler._heartbeat import (
    MAX_HEARTBEAT_HINT_MULTIPLIER,
    MIN_HEARTBEAT_INTERVAL_S,
    HeartbeatScheduler,
)


class _FakeDispatcher:
    def in_flight_count(self) -> int:
        return 0

    def in_flight_task_ids(self) -> set[int]:
        return set()

    def apply_platform_directive(self, directive: Any) -> None:
        return None

    def mark_cancel_requested(self, task_id: int, reason: str) -> None:
        return None


class _FakeHttp:
    async def heartbeat(self, worker_code: str, body: dict[str, Any]) -> dict[str, Any]:
        return {}


def _cfg(baseline_s: float) -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url="http://orch:8081",
        tenant_id="acme",
        worker_code="w-1",
        heartbeat_interval=timedelta(seconds=baseline_s),
        lease_renew_interval=timedelta(seconds=5),
        http_timeout=timedelta(seconds=1),
    )


def _make_scheduler(baseline_s: float) -> HeartbeatScheduler:
    return HeartbeatScheduler(_cfg(baseline_s), _FakeHttp(), _FakeDispatcher())  # type: ignore[arg-type]


@pytest.mark.parametrize(
    ("baseline_s", "hint_s", "expected_s"),
    [
        # 下限钳:hint 远小于 MIN → 钳到 MIN(防止 100ms 风暴)
        (30.0, 0.1, MIN_HEARTBEAT_INTERVAL_S),
        (30.0, 0.5, MIN_HEARTBEAT_INTERVAL_S),
        # 上限钳:hint 远大于 baseline * MAX → 钳到 baseline * MAX(防止饥饿)
        (30.0, 600.0, 30.0 * MAX_HEARTBEAT_HINT_MULTIPLIER),  # → 300s
        (10.0, 9999.0, 10.0 * MAX_HEARTBEAT_HINT_MULTIPLIER),  # → 100s
        # 正常范围:hint 原样放行
        (30.0, 15.0, 15.0),
        (30.0, 60.0, 60.0),
        # 精确边界:刚好等于 MIN / 等于 baseline * MAX
        (30.0, MIN_HEARTBEAT_INTERVAL_S, MIN_HEARTBEAT_INTERVAL_S),
        (
            30.0,
            30.0 * MAX_HEARTBEAT_HINT_MULTIPLIER,
            30.0 * MAX_HEARTBEAT_HINT_MULTIPLIER,
        ),
    ],
)
def test_apply_hint_clamps_to_bounded_range(
    baseline_s: float, hint_s: float, expected_s: float
) -> None:
    """nextHeartbeatHint 在 [MIN, baseline * MAX] 内放行,越界钳到边界。"""
    sched = _make_scheduler(baseline_s)
    assert sched.current_interval_s == pytest.approx(baseline_s)

    sched._apply_hint(hint_s)

    assert sched.current_interval_s == pytest.approx(expected_s)


def test_clamp_constants_match_java_contract() -> None:
    """常量值与 Java HeartbeatScheduler 端 MIN_HINT_MS=1000 / MAX_MULTIPLIER=10 对齐。"""
    assert MIN_HEARTBEAT_INTERVAL_S == 1.0
    assert MAX_HEARTBEAT_HINT_MULTIPLIER == 10
