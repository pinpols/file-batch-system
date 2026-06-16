"""Lane A #3 — TaskDispatcher.mark_cancel_requested 行为守护。

验证:

1. ``on_message`` 入队后,``_cancel_signals[task_id]`` 持有可翻的 signal。
2. ``mark_cancel_requested(task_id, reason)`` 翻 signal →
   ``is_cancellation_requested == True``。
3. ``is_cancel_requested(task_id)`` 与 signal 状态一致。
4. 重复 ``mark_cancel_requested`` 幂等(``CancellationSignal.mark_cancelled``
   底层 ``asyncio.Event.set`` 幂等)。
5. cleanup(任务完成)后再 ``mark_cancel_requested`` 走 DEBUG 分支不抛。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

import pytest

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher
from batch_worker_sdk.internal._http import PlatformHttpClient


def _cfg() -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url="http://orch:8081",
        tenant_id="acme",
        worker_code="w-1",
        max_concurrent_tasks=4,
    )


def _msg(task_id: int = 1) -> dict[str, Any]:
    return {
        "schemaVersion": "v2",
        "tenantId": "acme",
        "taskId": task_id,
        "jobCode": "daily",
        "workerType": "echo",
        "idempotencyKey": f"key-{task_id}",
    }


async def test_mark_cancel_flips_signal_for_in_flight_task() -> None:
    cfg = _cfg()
    blocker = asyncio.Event()

    async def slow_claim(*_a: Any, **_kw: Any) -> tuple[dict[str, Any], int]:
        await blocker.wait()
        return {}, 200

    async def fake_report(*_a: Any, **_kw: Any) -> dict[str, Any]:
        return {}

    http = PlatformHttpClient(cfg)
    http.claim_status = slow_claim  # type: ignore[method-assign]
    http.report = fake_report  # type: ignore[method-assign]
    dispatcher = TaskDispatcher(cfg, http)
    try:
        await dispatcher.on_message(_msg(task_id=123))
        await asyncio.sleep(0)  # let _process start

        # in-flight 必然带 signal
        signal = dispatcher._cancel_signals[123]
        assert signal.is_cancellation_requested is False
        assert dispatcher.is_cancel_requested(123) is False

        # 翻信号
        dispatcher.mark_cancel_requested(123, "platform-cancel")
        assert signal.is_cancellation_requested is True
        assert dispatcher.is_cancel_requested(123) is True

        # 幂等:再翻一次不抛
        dispatcher.mark_cancel_requested(123, "platform-cancel")
        assert signal.is_cancellation_requested is True

        blocker.set()
        await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)
    finally:
        await http.close()


async def test_mark_cancel_on_unknown_task_id_logs_debug_no_raise(
    caplog: pytest.LogCaptureFixture,
) -> None:
    # 模拟 cleanup 后(task 已结束)的 race:LeaseRenewalScheduler 用旧
    # task_id 调 mark_cancel_requested,应仅 DEBUG,不抛。
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    dispatcher = TaskDispatcher(cfg, http)
    try:
        caplog.set_level(logging.DEBUG, logger="batch_worker_sdk.dispatcher.dispatcher")
        # 没注入任何 in-flight,直接调
        dispatcher.mark_cancel_requested(9999, "platform-cancel")
        assert dispatcher.is_cancel_requested(9999) is False
        assert any("mark_cancel_requested for unknown task_id" in r.message for r in caplog.records)
    finally:
        await http.close()


async def test_cleanup_pops_signal_when_task_completes() -> None:
    cfg = _cfg()

    async def fast_claim(*_a: Any, **_kw: Any) -> tuple[dict[str, Any], int]:
        return {}, 200

    async def fast_report(*_a: Any, **_kw: Any) -> dict[str, Any]:
        return {}

    http = PlatformHttpClient(cfg)
    http.claim_status = fast_claim  # type: ignore[method-assign]
    http.report = fast_report  # type: ignore[method-assign]
    dispatcher = TaskDispatcher(cfg, http)
    try:
        await dispatcher.on_message(_msg(task_id=7))
        # 跑到 task 完成 + done_callback 执行
        await asyncio.gather(*list(dispatcher._in_flight.values()), return_exceptions=True)
        await asyncio.sleep(0)  # 让 done_callback 执行
        assert 7 not in dispatcher._cancel_signals
        assert dispatcher.is_cancel_requested(7) is False
    finally:
        await http.close()
