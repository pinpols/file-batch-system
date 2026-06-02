"""Per-task cancellation signal (P0.5 stub).

Mirrors Java ``com.example.batch.sdk.task.CancellationSignal``. P0.5
ships a single-threaded attribute-based implementation; P4 (lease
renewal scheduler) will swap to an ``asyncio.Event`` or ``threading``
primitive so the cancellation bit set by the lease-renewal task is
observable from the handler coroutine without a memory-model surprise.
"""

from __future__ import annotations


class CancellationSignal:
    """Single-bit cooperative cancellation flag for one task execution.

    Long-running handlers should poll :attr:`is_cancellation_requested`
    in their loop and return early when set, instead of waiting for the
    natural lease timeout.
    """

    def __init__(self) -> None:
        self._cancelled: bool = False

    @property
    def is_cancellation_requested(self) -> bool:
        """``True`` when the platform has asked this task to stop."""
        return self._cancelled

    def mark_cancelled(self) -> None:
        """Flip the signal — called by the lease-renewal scheduler (P4).

        Package-internal in spirit; tests may call it to simulate
        platform cancellation.
        """
        self._cancelled = True
