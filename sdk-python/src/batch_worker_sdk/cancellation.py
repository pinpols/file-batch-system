"""Per-task cancellation signal (P4 implementation).

Mirrors Java ``com.example.batch.sdk.task.CancellationSignal``. The Java
side relies on ``volatile boolean`` for cross-thread visibility between
the lease-renewal scheduler thread (which flips the bit on
``cancelRequested=true`` from a renew response) and the handler thread
(which polls in its long loop).

Python's equivalent is :class:`asyncio.Event`:

- ``set`` / ``is_set`` are atomic w.r.t. the asyncio loop,
- the event doubles as an **awaitable**, so a handler that needs to
  ``select``-style multiplex IO + cancellation can
  ``await signal.wait_cancelled()`` without busy-polling, and
- repeated ``mark_cancelled()`` calls are **idempotent**
  (``Event.set`` is a no-op after the first call).
"""

from __future__ import annotations

import asyncio


class CancellationSignal:
    """Single-bit cooperative cancellation flag for one task execution.

    Long-running handlers should poll :attr:`is_cancellation_requested`
    in their loop and return early when set, instead of waiting for the
    natural lease timeout. Async handlers that need to multiplex IO
    with cancellation can ``await wait_cancelled()`` directly.
    """

    __slots__ = ("_event",)

    def __init__(self) -> None:
        self._event: asyncio.Event = asyncio.Event()

    @property
    def is_cancellation_requested(self) -> bool:
        """``True`` when the platform has asked this task to stop."""
        return self._event.is_set()

    def mark_cancelled(self) -> None:
        """Flip the signal — called by the lease-renewal scheduler.

        Idempotent: subsequent calls are no-ops. Package-internal in
        spirit; tests may call it directly to simulate platform
        cancellation.
        """
        self._event.set()

    async def wait_cancelled(self) -> None:
        """Suspend until :meth:`mark_cancelled` has been called.

        Returns immediately if the signal is already set. Lets a
        handler multiplex IO with cancellation via :func:`asyncio.wait`:

        .. code-block:: python

            done, pending = await asyncio.wait(
                [
                    asyncio.create_task(do_work()),
                    asyncio.create_task(ctx.cancel_signal.wait_cancelled()),
                ],
                return_when=asyncio.FIRST_COMPLETED,
            )
        """
        await self._event.wait()
