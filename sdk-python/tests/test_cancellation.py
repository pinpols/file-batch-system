"""Tests for :class:`CancellationSignal` (Lane U / P4)."""

from __future__ import annotations

import asyncio

import pytest

from batch_worker_sdk import CancellationSignal


def test_initial_state_is_not_cancelled() -> None:
    sig = CancellationSignal()
    assert sig.is_cancellation_requested is False


def test_mark_cancelled_flips_flag() -> None:
    sig = CancellationSignal()
    sig.mark_cancelled()
    assert sig.is_cancellation_requested is True


def test_mark_cancelled_is_idempotent() -> None:
    sig = CancellationSignal()
    sig.mark_cancelled()
    sig.mark_cancelled()
    sig.mark_cancelled()
    assert sig.is_cancellation_requested is True


async def test_wait_cancelled_returns_immediately_when_already_set() -> None:
    sig = CancellationSignal()
    sig.mark_cancelled()
    # Should not block: wrap in a tight timeout to fail loudly if it does.
    await asyncio.wait_for(sig.wait_cancelled(), timeout=0.5)


async def test_wait_cancelled_blocks_until_set() -> None:
    sig = CancellationSignal()

    async def flip_after(delay: float) -> None:
        await asyncio.sleep(delay)
        sig.mark_cancelled()

    flipper = asyncio.create_task(flip_after(0.05))
    await asyncio.wait_for(sig.wait_cancelled(), timeout=1.0)
    await flipper
    assert sig.is_cancellation_requested is True


async def test_wait_cancelled_supports_multiple_waiters() -> None:
    sig = CancellationSignal()

    async def waiter() -> bool:
        await sig.wait_cancelled()
        return True

    tasks = [asyncio.create_task(waiter()) for _ in range(5)]
    await asyncio.sleep(0.01)  # let waiters block
    sig.mark_cancelled()
    results = await asyncio.wait_for(asyncio.gather(*tasks), timeout=1.0)
    assert results == [True] * 5


async def test_concurrent_mark_cancelled_is_safe() -> None:
    sig = CancellationSignal()

    async def marker() -> None:
        sig.mark_cancelled()

    await asyncio.gather(*(marker() for _ in range(50)))
    assert sig.is_cancellation_requested is True


def test_slots_prevent_arbitrary_attributes() -> None:
    sig = CancellationSignal()
    with pytest.raises(AttributeError):
        sig.arbitrary_field = "nope"  # type: ignore[attr-defined]
