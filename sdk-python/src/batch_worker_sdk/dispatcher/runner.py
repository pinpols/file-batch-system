"""把 HTTP + Dispatcher + Kafka 消费者粘合到一起的临时 run-loop。

# 临时入口 —— 将由 BatchPlatformClient 取代

本模块的存在是为了让 testkit 使用者和契约测试 runner 有一个可启动的对象。
推荐迁移到 :class:`BatchPlatformClient`(自带 register / 心跳 / 租约续约调度
+ 完整生命周期);本入口将退化为薄 shim,转调
``BatchPlatformClient.run_forever()``。
"""

from __future__ import annotations

import asyncio
import logging

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher
from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.internal._kafka import KafkaTaskConsumer

logger = logging.getLogger(__name__)


async def run_worker(
    config: BatchPlatformClientConfig,
    handlers: dict[str, SdkTaskHandler] | None = None,
    *,
    shutdown_timeout: float = 30.0,
) -> None:
    """运行 SDK 直到被取消 / 收到 KeyboardInterrupt。

    临时入口,把三个基础部件组合起来:``PlatformHttpClient``(HTTP)+
    ``TaskDispatcher``(CLAIM→REPORT)+ ``KafkaTaskConsumer``(派发摄入)。
    不含 register / 心跳循环 —— 那部分在 :class:`BatchPlatformClient` 内。

    Args:
        config: 已校验的配置,需包含 ``kafka_*`` 字段。
        handlers: ``workerType → SdkTaskHandler`` 路由表;smoke test 可传空,
            缺 handler 的消息会按 REPORT failure 处理。
        shutdown_timeout: ``CancelledError`` 时等待 in-flight drain 的秒数,
            超时则强制关闭。
    """
    http = PlatformHttpClient(config)
    dispatcher = TaskDispatcher(config, http, handlers=handlers)
    consumer = KafkaTaskConsumer(config, dispatcher)
    try:
        await consumer.start()
        # 永远阻塞,外层任务取消时触发关停。
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            logger.info("run_worker cancelled, shutting down")
            raise
    finally:
        await consumer.stop()
        await dispatcher.shutdown(timeout=shutdown_timeout)
        await http.close()
