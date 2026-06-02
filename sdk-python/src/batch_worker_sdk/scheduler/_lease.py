"""异步租约续约调度器。

对应 Java ``com.example.batch.sdk.scheduler.LeaseRenewalScheduler``,作为
长生命周期的 ``asyncio.Task`` 由
:class:`~batch_worker_sdk.client.client.BatchPlatformClient.start` 启动。

单任务语义:

- 响应中 ``cancelRequested=True`` → 触发 dispatcher 侧的取消信号
  (``mark_cancel_requested``)。
- 404 / 410 → 租约已被撤销;同样按取消处理。
- 其他失败 → WARN,等下次 tick 重试。
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from typing import Any

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import PlatformError
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.scheduler._heartbeat import DispatcherLike

logger = logging.getLogger(__name__)


class LeaseRenewalScheduler:
    """按 in-flight 任务批量续约。

    Java 对应类:``LeaseRenewalScheduler``。

    Args:
        config: 已校验的 SDK 配置(提供 ``lease_renew_interval``)。
        http: 已建立连接的 :class:`PlatformHttpClient`。
        dispatcher: 满足 :class:`DispatcherLike` 协议的 dispatcher。
    """

    def __init__(
        self,
        config: BatchPlatformClientConfig,
        http: PlatformHttpClient,
        dispatcher: DispatcherLike,
    ) -> None:
        self._config = config
        self._http = http
        self._dispatcher = dispatcher
        self._interval_s: float = config.lease_renew_interval.total_seconds()
        self._task: asyncio.Task[None] | None = None
        self._stop_event: asyncio.Event = asyncio.Event()

    @property
    def running(self) -> bool:
        return self._task is not None and not self._task.done()

    async def start(self) -> None:
        """启动租约续约循环,幂等。"""
        if self.running:
            logger.debug("LeaseRenewalScheduler already running, ignoring start()")
            return
        self._stop_event.clear()
        self._task = asyncio.create_task(self._run_loop(), name="sdk-lease-renewal")
        logger.info("LeaseRenewalScheduler started: interval=%.3fs", self._interval_s)

    async def stop(self) -> None:
        """请求停止并等待循环退出,可重复调用。"""
        if self._task is None:
            return
        self._stop_event.set()
        try:
            await asyncio.wait_for(self._task, timeout=self._interval_s + 1.0)
        except TimeoutError:
            logger.warning("LeaseRenewalScheduler did not exit cleanly; cancelling")
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._task
        self._task = None
        logger.info("LeaseRenewalScheduler stopped")

    async def tick(self) -> None:
        """对每个 in-flight 租约续约一次,单任务失败相互隔离。"""
        ids = self._dispatcher.in_flight_task_ids()
        if not ids:
            return
        # 排序后的副本上迭代:既能让测试断言确定,又能避免在 dispatcher
        # 后台完成任务时边迭代边改 dict。
        for task_id in sorted(ids):
            await self._renew_one(task_id)

    async def _renew_one(self, task_id: int) -> None:
        body: dict[str, Any] = {
            "tenantId": self._config.tenant_id,
            "workerId": self._config.worker_code,
        }
        try:
            resp = await self._http.renew(task_id, body)
        except PlatformError as ex:
            # ``_http.renew`` 把 404/410 转成 ConflictError/Persistent;
            # 取 statusCode(若有)判断,否则仅 WARN。
            status = getattr(ex, "status_code", None)
            if status in (404, 410):
                logger.warning(
                    "lease revoked for taskId=%s (HTTP %s) — signalling stop",
                    task_id,
                    status,
                )
                self._safe_mark_cancel(task_id, "lease-revoked")
            else:
                logger.warning("renew failed for taskId=%s: %s", task_id, ex)
            return
        except Exception as ex:
            logger.warning("renew unexpected error for taskId=%s: %s", task_id, ex)
            return

        if resp and resp.get("cancelRequested") is True:
            logger.info("platform requested cancel for taskId=%s", task_id)
            self._safe_mark_cancel(task_id, "platform-cancel")

    def _safe_mark_cancel(self, task_id: int, reason: str) -> None:
        """转发取消信号到 dispatcher;当前 dispatcher 还未提供该方法时降级 WARN。

        使用 ``getattr`` 探测保持向前兼容:dispatcher 后续会补
        ``mark_cancel_requested``,在此之前缺失即 WARN 提示,避免静默。
        """
        mark = getattr(self._dispatcher, "mark_cancel_requested", None)
        if mark is None:
            logger.warning(
                "dispatcher missing mark_cancel_requested(taskId=%s, reason=%s) — "
                "cancel signal not yet wired",
                task_id,
                reason,
            )
            return
        try:
            mark(task_id, reason)
        except Exception as ex:
            logger.warning("mark_cancel_requested raised for taskId=%s: %s", task_id, ex)

    async def _run_loop(self) -> None:
        try:
            while not self._stop_event.is_set():
                try:
                    await asyncio.wait_for(
                        self._stop_event.wait(),
                        timeout=self._interval_s,
                    )
                    return
                except TimeoutError:
                    pass
                try:
                    await self.tick()
                except Exception as ex:
                    logger.warning("lease renewal tick failed: %s", ex)
        except asyncio.CancelledError:
            raise
