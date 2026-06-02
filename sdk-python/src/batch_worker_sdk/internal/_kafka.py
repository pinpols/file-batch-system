"""Kafka task dispatch consumer — asyncio port of the Java consumer.

Python equivalent of
``com.example.batch.sdk.dispatcher.KafkaTaskConsumer`` (see Java file
for the long-form design notes). Differences vs Java, by design:

- ``aiokafka.AIOKafkaConsumer`` (not ``KafkaConsumer``) — single
  asyncio task drives the poll loop, no background thread.
- ``getmany(timeout_ms=...)`` instead of ``poll(Duration)`` so we can
  control batch size + explicitly commit per batch.
- Capacity-aware partition pause uses ``consumer.pause(*tps)`` /
  ``consumer.resume(*tps)``; rebalance handler resets ``_paused``
  cache so a partition that left/rejoined the assignment doesn't end
  up stuck in a stale paused state.
- ``enable_auto_commit=False`` + ``commit()`` after dispatcher accepts
  each batch (mirrors Java ``commitSync()`` semantics).
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
    """Rebalance hook — clear paused cache on assignment change.

    aiokafka's ``pause(...)`` is per-TopicPartition and survives across
    polls but **not** across rebalances. So we reset ``_paused`` to
    ``False`` whenever a rebalance reshuffles us; the next
    ``apply_backpressure`` tick will re-pause if still saturated.
    """

    def __init__(self, parent: KafkaTaskConsumer) -> None:
        self._parent = parent

    async def on_partitions_revoked(self, revoked: set[TopicPartition]) -> None:
        logger.info("kafka rebalance: partitions revoked=%s", sorted(map(str, revoked)))

    async def on_partitions_assigned(self, assigned: set[TopicPartition]) -> None:
        logger.info("kafka rebalance: partitions assigned=%s", sorted(map(str, assigned)))
        # Pause state is per-partition in aiokafka; reset cache so the
        # next backpressure tick re-evaluates against the new assignment.
        self._parent._paused = False


class KafkaTaskConsumer:
    """Long-running async consumer driving ``TaskDispatcher.on_message``.

    Args:
        config: Validated SDK config. ``kafka_bootstrap``,
            ``kafka_group_id`` and ``kafka_topic_pattern`` are
            mandatory for ``start()``; ``start()`` raises if any are
            missing.
        dispatcher: The dispatcher to feed. ``in_flight_count()`` +
            ``accepts_new_tasks()`` drive partition pause/resume.
        consumer: Optional preconstructed ``AIOKafkaConsumer`` for
            tests. When ``None``, ``start()`` builds one from config.

    Lifecycle:

    - ``await start()`` builds + subscribes the consumer and launches
      the poll loop as a background task. Returns immediately.
    - ``await stop()`` requests loop exit, awaits the poll task, and
      closes the consumer. Idempotent.
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

    # ─── public lifecycle ──────────────────────────────────────────────

    async def start(self) -> None:
        """Build (if needed), subscribe, and start polling."""
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
        """Request loop exit + close consumer. Idempotent."""
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

    # ─── internals: build & subscribe ──────────────────────────────────

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
            # aiokafka splits jaas into plain user/password rather than
            # a single jaas string. P3 will parse "username=...
            # password=..." out of the Java-style jaas_config to bridge;
            # for now we just forward it via the catch-all kwarg so
            # operators can override per-tenant in tests.
            kwargs["sasl_plain_username"] = ""
            kwargs["sasl_plain_password"] = self._config.kafka_sasl_jaas_config
        return AIOKafkaConsumer(**kwargs)

    def _subscribe(self) -> None:
        assert self._consumer is not None
        pattern = self._config.kafka_topic_pattern
        assert pattern is not None  # validated in _build_consumer
        self._consumer.subscribe(
            pattern=pattern,
            listener=_PauseAwareRebalanceListener(self),
        )

    # ─── internals: poll loop ──────────────────────────────────────────

    async def _poll_loop(self) -> None:
        """Single-task poll loop. Exits on ``self._running == False``."""
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
        """Decode one ``ConsumerRecord`` and feed the dispatcher."""
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

    # ─── capacity-aware partition pause ────────────────────────────────

    def apply_backpressure(self) -> None:
        """Pause/resume assignment based on dispatcher capacity.

        Mirrors Java ``KafkaTaskConsumer.applyBackpressure()``: when
        in-flight reaches ``max_concurrent_tasks`` or the platform
        directive moved us to PAUSED/DRAINING, we ``pause(*assignment)``
        so the broker stops fetching for these partitions; once
        capacity returns we ``resume(*assignment)``. Offsets are never
        committed for the unprocessed message — Kafka will redeliver
        on resume.

        The ``_paused`` field caches the last call so we don't issue a
        pause/resume RPC every poll. Rebalances reset it via the
        listener.
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

    # ─── test/observability hooks ──────────────────────────────────────

    @property
    def paused(self) -> bool:
        """Last cached pause decision (for tests + diagnostics)."""
        return self._paused

    @property
    def running(self) -> bool:
        return self._running
