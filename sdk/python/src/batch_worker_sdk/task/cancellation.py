"""单任务取消信号(P4 实现)。

对齐 Java ``io.github.pinpols.batch.sdk.task.CancellationSignal``。Java 侧用
``volatile boolean`` 保证跨线程可见性:租约续期调度器线程在 renew 响应里
看到 ``cancelRequested=true`` 时翻转 bit,handler 线程在长循环里轮询。

Python 对等物是 :class:`asyncio.Event`:

- ``set`` / ``is_set`` 在 asyncio loop 内是原子的;
- event 同时是 **awaitable**,需要 ``select`` 风格多路复用 IO + 取消的
  handler 可以 ``await signal.wait_cancelled()``,不必忙轮询;
- 重复调用 ``mark_cancelled()`` 是 **幂等** 的(``Event.set`` 首次之后是 no-op)。
"""

from __future__ import annotations

import asyncio


class CancellationSignal:
    """单任务执行的协作式取消单 bit 标志。

    长跑 handler 应在循环里轮询 :attr:`is_cancellation_requested`,置位
    时尽早返回,而非等到租约自然超时。需要把 IO 和取消同时多路复用的
    异步 handler 可以直接 ``await wait_cancelled()``。
    """

    __slots__ = ("_event",)

    def __init__(self) -> None:
        self._event: asyncio.Event = asyncio.Event()

    @property
    def is_cancellation_requested(self) -> bool:
        """平台请求此任务停止时返回 ``True``。"""
        return self._event.is_set()

    def mark_cancelled(self) -> None:
        """翻转信号 —— 由租约续期调度器调用。

        幂等:再次调用为 no-op。语义上是包内私有;测试可以直接调它来模拟
        平台取消。
        """
        self._event.set()

    async def wait_cancelled(self) -> None:
        """挂起直到有人调用 :meth:`mark_cancelled`。

        若信号已置位则立即返回。让 handler 通过 :func:`asyncio.wait` 把 IO
        与取消多路复用:

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
