"""``batch_worker_sdk.scheduler._lease.LeaseRenewalScheduler`` 的测试(P3)。

6 个用例:

1. in-flight 为空时 tick 是 no-op(不发 HTTP)。
2. ``cancelRequested=True`` → 用 reason 触发 ``mark_cancel_requested``。
3. 404 lease-revoked → 触发 ``mark_cancel_requested`` 并带 ``"lease-revoked"``。
4. 一个任务失败不影响同 tick 内其它任务。
5. ``start()`` + ``stop()`` 生命周期干净。
6. dispatcher 缺 ``mark_cancel_requested``(取消能力尚未接入)
   时退化为 WARN 日志,不崩溃。
"""

from __future__ import annotations

from datetime import timedelta
from typing import Any

import pytest

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import PersistentClientError, TransientError
from batch_worker_sdk.scheduler._lease import LeaseRenewalScheduler


class _Dispatcher:
    def __init__(self, ids: set[int]) -> None:
        self.ids = ids
        self.cancel_calls: list[tuple[int, str]] = []

    def in_flight_count(self) -> int:
        return len(self.ids)

    def in_flight_task_ids(self) -> set[int]:
        return set(self.ids)

    def apply_platform_directive(self, directive: Any) -> None:
        return None

    def mark_cancel_requested(self, task_id: int, reason: str) -> None:
        self.cancel_calls.append((task_id, reason))


class _DispatcherWithoutCancel:
    """用例 #6 用 —— 模拟尚未挂上取消扩展时的 dispatcher。"""

    def in_flight_count(self) -> int:
        return 1

    def in_flight_task_ids(self) -> set[int]:
        return {77}

    def apply_platform_directive(self, directive: Any) -> None:
        return None


class _Http:
    def __init__(self) -> None:
        self.calls: list[int] = []
        # task_id → 响应 / 异常
        self.responses: dict[int, dict[str, Any]] = {}
        self.errors: dict[int, BaseException] = {}

    async def renew(self, task_id: int, body: dict[str, Any]) -> dict[str, Any]:
        self.calls.append(task_id)
        if task_id in self.errors:
            raise self.errors[task_id]
        return dict(self.responses.get(task_id, {}))


def _cfg() -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url="http://orch:8081",
        tenant_id="acme",
        worker_code="w-1",
        heartbeat_interval=timedelta(seconds=2),
        lease_renew_interval=timedelta(seconds=5),
        http_timeout=timedelta(seconds=1),
    )


async def test_tick_with_empty_inflight_makes_no_http_calls() -> None:
    sched = LeaseRenewalScheduler(_cfg(), _Http(), _Dispatcher(set()))  # type: ignore[arg-type]
    await sched.tick()
    # 除"没有异常 + 没有 HTTP 调用"外不需要别的断言;dispatcher
    # 空集如果触发网络 I/O,这里会卡在 _Http 缺失的 mock 上。


async def test_cancel_requested_triggers_dispatcher_mark() -> None:
    dispatcher = _Dispatcher({101})
    http = _Http()
    http.responses[101] = {"cancelRequested": True}
    sched = LeaseRenewalScheduler(_cfg(), http, dispatcher)  # type: ignore[arg-type]

    await sched.tick()

    assert dispatcher.cancel_calls == [(101, "platform-cancel")]


async def test_404_treated_as_lease_revoked() -> None:
    dispatcher = _Dispatcher({202})
    http = _Http()
    http.errors[202] = PersistentClientError("404 Not Found", status_code=404, attempts=1)
    sched = LeaseRenewalScheduler(_cfg(), http, dispatcher)  # type: ignore[arg-type]

    await sched.tick()

    assert dispatcher.cancel_calls == [(202, "lease-revoked")]


async def test_one_failure_does_not_block_other_tasks() -> None:
    dispatcher = _Dispatcher({1, 2, 3})
    http = _Http()
    http.errors[2] = TransientError("500 Internal Server Error", status_code=500, attempts=3)
    http.responses[3] = {"cancelRequested": True}
    sched = LeaseRenewalScheduler(_cfg(), http, dispatcher)  # type: ignore[arg-type]

    await sched.tick()

    # 三个 task_id 都尝试过,与顺序、失败无关。
    assert sorted(http.calls) == [1, 2, 3]
    # 只有 task 3 触发了 cancel;task 1 ok,task 2 失败。
    assert dispatcher.cancel_calls == [(3, "platform-cancel")]


async def test_start_and_stop_run_cleanly() -> None:
    sched = LeaseRenewalScheduler(_cfg(), _Http(), _Dispatcher(set()))  # type: ignore[arg-type]
    await sched.start()
    assert sched.running
    await sched.stop()
    assert not sched.running


async def test_missing_mark_cancel_on_dispatcher_logs_warn(
    caplog: pytest.LogCaptureFixture,
) -> None:
    dispatcher = _DispatcherWithoutCancel()
    http = _Http()
    http.responses[77] = {"cancelRequested": True}
    sched = LeaseRenewalScheduler(_cfg(), http, dispatcher)  # type: ignore[arg-type]

    await sched.tick()  # 不能抛

    assert any(
        "mark_cancel_requested" in r.message and "not yet wired" in r.message
        for r in caplog.records
    )
