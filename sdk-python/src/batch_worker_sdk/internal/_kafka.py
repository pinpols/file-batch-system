"""Kafka 任务派发消费者 —— Java 端消费者的 asyncio 移植版本。

对应 Java ``com.example.batch.sdk.dispatcher.KafkaTaskConsumer``(详细设
计说明见 Java 源码)。刻意的差异:

- 使用 ``aiokafka.AIOKafkaConsumer``(而非阻塞式 ``KafkaConsumer``)——
  单条 asyncio task 驱动 poll 循环,无后台线程。
- ``getmany(timeout_ms=...)`` 替代 ``poll(Duration)``,以便控制批量大小
  并按 batch 显式 commit。
- 容量感知的分区暂停走 ``consumer.pause(*tps)`` / ``consumer.resume(*tps)``;
  rebalance 回调里清空 ``_paused`` 缓存,避免一个被踢出又分回来的分区
  停在过期的 paused 状态。
- ``enable_auto_commit=False`` + 每批 dispatcher 处理完后调 ``commit()``
  (对齐 Java ``commitSync()`` 语义)。
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from typing import TYPE_CHECKING, Any

from aiokafka import (  # type: ignore[import-untyped]
    AIOKafkaConsumer,
    ConsumerRebalanceListener,
    TopicPartition,
)

from batch_worker_sdk.client.config import BatchPlatformClientConfig

if TYPE_CHECKING:
    from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher

logger = logging.getLogger(__name__)


def _notblank(s: str | None) -> bool:
    return s is not None and s.strip() != ""


class _PauseAwareRebalanceListener(ConsumerRebalanceListener):
    """rebalance 钩子 —— 分区分配变化时清空 paused 缓存。

    aiokafka 的 ``pause(...)`` 是按 TopicPartition 维度的,在 poll 之间会
    保留但在 **rebalance** 时失效。因此 rebalance 后我们把 ``_paused``
    重置为 ``False``;若仍处饱和状态,下一次 ``apply_backpressure`` 会
    重新 pause。
    """

    def __init__(self, parent: KafkaTaskConsumer) -> None:
        self._parent = parent

    async def on_partitions_revoked(self, revoked: set[TopicPartition]) -> None:
        logger.info("kafka rebalance: partitions revoked=%s", sorted(map(str, revoked)))

    async def on_partitions_assigned(self, assigned: set[TopicPartition]) -> None:
        logger.info("kafka rebalance: partitions assigned=%s", sorted(map(str, assigned)))
        # aiokafka 的 pause 状态是按 partition 维度的;清空缓存,让下一次
        # backpressure tick 在新分配上重新评估。
        self._parent._paused = False


class KafkaTaskConsumer:
    """常驻 async 消费者,把 Kafka 消息喂给 ``TaskDispatcher.on_message``。

    Args:
        config: 已校验的 SDK 配置。``kafka_bootstrap``、``kafka_group_id``、
            ``kafka_topic_pattern`` 是 ``start()`` 的必填项,缺一即抛错。
        dispatcher: 待喂入的 dispatcher。``in_flight_count()`` +
            ``accepts_new_tasks()`` 共同驱动分区 pause/resume。
        consumer: 可选的预构造 ``AIOKafkaConsumer``,主要供测试场景;
            ``None`` 时由 ``start()`` 按配置构造。

    生命周期:

    - ``await start()`` 构造并 subscribe consumer,启动后台 poll 循环
      并立即返回。
    - ``await stop()`` 请求循环退出、等待 poll task、关闭 consumer,幂等。
    """

    def __init__(
        self,
        config: BatchPlatformClientConfig,
        dispatcher: TaskDispatcher,
        *,
        consumer: AIOKafkaConsumer | None = None,
    ) -> None:
        self._config = config
        self._dispatcher = dispatcher
        self._consumer: AIOKafkaConsumer | None = consumer
        self._owns_consumer = consumer is None
        self._running: bool = False
        self._paused: bool = False
        self._poll_task: asyncio.Task[None] | None = None

    # ─── 公共生命周期 ────────────────────────────────────────────────

    async def start(self) -> None:
        """按需构造 consumer、subscribe topic、启动 poll 循环。"""
        if self._running:
            return
        if self._consumer is None:
            self._consumer = self._build_consumer()
        await self._consumer.start()
        self._subscribe()
        self._running = True
        self._poll_task = asyncio.create_task(self._poll_loop(), name="kafka-poll-loop")
        logger.info(
            "KafkaTaskConsumer started: tenant=%s, topicPattern=%s, group=%s",
            self._config.tenant_id,
            self._config.kafka_topic_pattern,
            self._config.kafka_group_id,
        )

    async def stop(self) -> None:
        """请求 poll 循环退出并关闭 consumer,幂等。"""
        if not self._running:
            return
        self._running = False
        if self._poll_task is not None:
            try:
                await asyncio.wait_for(self._poll_task, timeout=10.0)
            except TimeoutError:
                logger.warning("kafka poll task did not exit within 10s; cancelling")
                self._poll_task.cancel()
                with contextlib.suppress(asyncio.CancelledError, Exception):
                    await self._poll_task
            self._poll_task = None
        if self._consumer is not None and self._owns_consumer:
            try:
                await self._consumer.stop()
            except Exception as ex:
                logger.warning("kafka consumer close error: %s", ex)
        logger.info("KafkaTaskConsumer stopped")

    # ─── 内部:构造 + subscribe ──────────────────────────────────────

    def _build_consumer(self) -> AIOKafkaConsumer:
        if not _notblank(self._config.kafka_bootstrap):
            raise ValueError("kafka_bootstrap must be set to start KafkaTaskConsumer")
        if not _notblank(self._config.kafka_group_id):
            raise ValueError("kafka_group_id must be set to start KafkaTaskConsumer")
        if not _notblank(self._config.kafka_topic_pattern):
            raise ValueError("kafka_topic_pattern must be set to start KafkaTaskConsumer")

        kwargs: dict[str, Any] = {
            "bootstrap_servers": self._config.kafka_bootstrap,
            "group_id": self._config.kafka_group_id,
            "enable_auto_commit": False,
            "auto_offset_reset": "latest",
        }
        if _notblank(self._config.kafka_security_protocol):
            kwargs["security_protocol"] = self._config.kafka_security_protocol
        if _notblank(self._config.kafka_sasl_mechanism):
            kwargs["sasl_mechanism"] = self._config.kafka_sasl_mechanism
        if _notblank(self._config.kafka_sasl_jaas_config):
            # aiokafka 不接受单一 jaas 字符串,而是分别接 plain user/password。
            # 后续会解析 Java 风格的 ``username=... password=...`` 自动转
            # 换;当前先把原值塞进 ``sasl_plain_password``,运维侧可在测试
            # 中按需覆盖。
            kwargs["sasl_plain_username"] = ""
            kwargs["sasl_plain_password"] = self._config.kafka_sasl_jaas_config
        return AIOKafkaConsumer(**kwargs)

    def _subscribe(self) -> None:
        assert self._consumer is not None
        pattern = self._config.kafka_topic_pattern
        assert pattern is not None  # _build_consumer 已校验
        self._consumer.subscribe(
            pattern=pattern,
            listener=_PauseAwareRebalanceListener(self),
        )

    # ─── 内部:poll 循环 ─────────────────────────────────────────────

    async def _poll_loop(self) -> None:
        """单任务的 poll 循环;``self._running == False`` 时退出。"""
        assert self._consumer is not None
        poll_ms = int(self._config.kafka_poll_interval.total_seconds() * 1000)
        try:
            while self._running:
                self.apply_backpressure()
                batches = await self._consumer.getmany(timeout_ms=poll_ms, max_records=64)
                if not batches:
                    continue
                for tp, records in batches.items():
                    for rec in records:
                        await self._handle_record(tp, rec)
                try:
                    await self._consumer.commit()
                except Exception as ex:
                    logger.warning("kafka commit failed (will retry next poll): %s", ex)
        except asyncio.CancelledError:
            logger.info("kafka poll loop cancelled")
            raise
        except Exception:
            logger.exception("KafkaTaskConsumer poll loop died")
            self._running = False

    async def _handle_record(self, tp: TopicPartition, rec: Any) -> None:
        """解码单条 ``ConsumerRecord`` 并喂给 dispatcher。"""
        value = rec.value
        if not value:
            logger.warning(
                "empty kafka message at topic=%s offset=%s, skipping",
                tp.topic,
                rec.offset,
            )
            return
        try:
            msg = json.loads(value)
        except (ValueError, TypeError) as ex:
            logger.error(
                "failed to parse kafka task dispatch message at topic=%s offset=%s: %s",
                tp.topic,
                rec.offset,
                ex,
            )
            return
        if not isinstance(msg, dict):
            logger.error(
                "kafka message at topic=%s offset=%s is not a JSON object: %r",
                tp.topic,
                rec.offset,
                type(msg).__name__,
            )
            return
        await self._dispatcher.on_message(msg)

    # ─── 容量感知的分区暂停 ───────────────────────────────────────────

    def apply_backpressure(self) -> None:
        """根据 dispatcher 容量决定 pause/resume 分配。

        对齐 Java ``KafkaTaskConsumer.applyBackpressure()``:当 in-flight
        达到 ``max_concurrent_tasks`` 或平台 directive 把状态切到
        PAUSED/DRAINING 时,``pause(*assignment)`` 让 broker 停止为这些分
        区拉取数据;容量恢复后 ``resume(*assignment)``。未处理消息的
        offset 永不提交,Kafka 会在 resume 后重投递。

        ``_paused`` 字段缓存上次决策,避免每次 poll 都发 pause/resume RPC;
        rebalance 时由 listener 清空缓存。
        """
        assert self._consumer is not None
        max_in_flight = self._config.max_concurrent_tasks
        in_flight = self._dispatcher.in_flight_count()
        should_pause = in_flight >= max_in_flight or not self._dispatcher.accepts_new_tasks()
        assignment = set(self._consumer.assignment())
        if not assignment:
            return
        if should_pause and not self._paused:
            self._consumer.pause(*assignment)
            self._paused = True
            logger.info(
                "kafka consumer pause: inFlight=%d max=%d state=%s",
                in_flight,
                max_in_flight,
                self._dispatcher.runtime_state,
            )
        elif not should_pause and self._paused:
            self._consumer.resume(*assignment)
            self._paused = False
            logger.info(
                "kafka consumer resume: inFlight=%d max=%d state=%s",
                in_flight,
                max_in_flight,
                self._dispatcher.runtime_state,
            )

    # ─── 测试 / 可观测性钩子 ──────────────────────────────────────────

    @property
    def paused(self) -> bool:
        """最近一次缓存的 pause 决策(供测试 + 诊断使用)。"""
        return self._paused

    @property
    def running(self) -> bool:
        return self._running
