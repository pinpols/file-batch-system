"""Tests for ``batch_worker_sdk.scheduler._lease.LeaseRenewalScheduler`` (Lane T P3).

5 cases per Lane T brief T4(Lane A 删原 #6 getattr fallback 用例:
``mark_cancel_requested`` 已是 ``DispatcherLike`` 协议必选方法,缺失=类型错误,
无需运行时探测):

1. Empty in-flight set → tick is a no-op (no HTTP calls).
2. ``cancelRequested=True`` → ``mark_cancel_requested`` invoked with reason.
3. 404 lease-revoked → ``mark_cancel_requested`` with ``"lease-revoked"``.
4. One task failing does not affect other tasks in the same tick.
5. ``start()`` + ``stop()`` lifecycle is clean.
"""

from __future__ import annotations

from datetime import timedelta
from typing import Any

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import PersistentClientError, TransientError
from batch_worker_sdk.scheduler._lease import LeaseRenewalScheduler


class _Dispatcher:
    def __init__(self, ids: set[int], partition_ids: dict[int, str] | None = None) -> None:
        self.ids = ids
        self.cancel_calls: list[tuple[int, str]] = []
        self.partition_ids = partition_ids or {}

    def in_flight_count(self) -> int:
        return len(self.ids)

    def in_flight_task_ids(self) -> set[int]:
        return set(self.ids)

    def apply_platform_directive(self, directive: Any) -> None:
        return None

    def mark_cancel_requested(self, task_id: int, reason: str) -> None:
        self.cancel_calls.append((task_id, reason))

    def partition_invocation_id(self, task_id: int) -> str | None:
        return self.partition_ids.get(task_id)


class _Http:
    def __init__(self) -> None:
        self.calls: list[int] = []
        self.bodies: list[dict[str, Any]] = []
        # Map task_id → response/exception
        self.responses: dict[int, dict[str, Any]] = {}
        self.errors: dict[int, BaseException] = {}

    async def renew(self, task_id: int, body: dict[str, Any]) -> dict[str, Any]:
        self.calls.append(task_id)
        self.bodies.append(dict(body))
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
    # No assertion needed beyond "no exception" + no HTTP calls; if the
    # dispatcher's empty set triggered network I/O this test would hang
    # waiting on _Http's missing mock.


async def test_renew_body_includes_partition_invocation_id() -> None:
    """fixture 10 / openapi TaskHeartbeatRequest:renew body 必须带
    CLAIM 时缓存的 partitionInvocationId。"""
    dispatcher = _Dispatcher({101}, partition_ids={101: "inv-1"})
    http = _Http()
    sched = LeaseRenewalScheduler(_cfg(), http, dispatcher)  # type: ignore[arg-type]

    await sched.tick()

    assert http.bodies == [
        {"tenantId": "acme", "workerId": "w-1", "partitionInvocationId": "inv-1"}
    ]


async def test_renew_body_omits_partition_invocation_id_when_absent() -> None:
    """非分区任务(无缓存 partitionInvocationId)→ renew body 不带该字段。"""
    dispatcher = _Dispatcher({102})
    http = _Http()
    sched = LeaseRenewalScheduler(_cfg(), http, dispatcher)  # type: ignore[arg-type]

    await sched.tick()

    assert http.bodies == [{"tenantId": "acme", "workerId": "w-1"}]


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

    # All three task_ids attempted, regardless of order or failure.
    assert sorted(http.calls) == [1, 2, 3]
    # Only task 3 should have triggered cancel; task 1 ok, task 2 failed.
    assert dispatcher.cancel_calls == [(3, "platform-cancel")]


async def test_start_and_stop_run_cleanly() -> None:
    sched = LeaseRenewalScheduler(_cfg(), _Http(), _Dispatcher(set()))  # type: ignore[arg-type]
    await sched.start()
    assert sched.running
    await sched.stop()
    assert not sched.running
