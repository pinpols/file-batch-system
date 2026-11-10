"""Live Kafka + HTTP fake 接通测试。

默认无 ``BATCH_SDK_KAFKA_BOOTSTRAP`` 时跳过;CI 的 sdk live gate 会启动真实 broker 后
显式运行。本用例对齐 Java ``FakeBatchPlatformSelfTest`` 的关键链路:
真实 Kafka 派单 → Python SDK consumer → dispatcher CLAIM → handler 执行 →
REPORT 回到 fake platform。
"""

from __future__ import annotations

import asyncio
import json
import os
import time
from datetime import timedelta
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest
from aiokafka import AIOKafkaProducer, TopicPartition
from aiokafka.admin import AIOKafkaAdminClient, NewTopic

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher
from batch_worker_sdk.dispatcher.dispatcher import DispatchDisposition
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.internal._kafka import KafkaTaskConsumer
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult
from batch_worker_sdk.testkit import FakeBatchPlatform


class EchoHandler:
    def task_type(self) -> str:
        return "echo"

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with(
            output={"echo": ctx.parameters.get("value")},
            message="echo-ok",
        )


async def _wait_until(predicate, timeout_s: float = 20.0) -> None:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if predicate():
            return
        await asyncio.sleep(0.1)
    raise AssertionError("timed out waiting for live SDK pipeline")


async def _wait_for_committed(
    consumer: KafkaTaskConsumer, tp: TopicPartition, expected: int
) -> None:
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if await consumer._consumer.committed(tp) == expected:  # type: ignore[union-attr]
            return
        await asyncio.sleep(0.1)
    raise AssertionError(f"partition {tp} did not reach offset {expected}")


async def _start_live_consumer(
    cfg: Any, dispatcher: Any, handler: Any, tp: TopicPartition
) -> KafkaTaskConsumer:
    """启动指定测试消费者并等待目标分区分配。"""
    consumer = KafkaTaskConsumer(cfg, dispatcher)
    consumer._handle_record = handler  # type: ignore[method-assign]
    await consumer.start()
    await _wait_until(lambda: tp in consumer._consumer.assignment())  # type: ignore[union-attr]
    return consumer


def _anchor_failure_fixture(
    bootstrap: str,
) -> tuple[str, TopicPartition, BatchPlatformClientConfig]:
    """构造首次位置失败测试的独立主题和消费组。"""
    suffix = str(time.time_ns())
    topic = f"batch.task.dispatch.python-anchor-failure-{suffix}"
    cfg = BatchPlatformClientConfig(
        base_url="http://localhost:8081",
        tenant_id="acme",
        worker_code=f"anchor-failure-{suffix}",
        max_concurrent_tasks=4,
        kafka_bootstrap=bootstrap,
        kafka_group_id=f"python-anchor-failure-{suffix}",
        kafka_topic_pattern=rf"^batch\.task\.dispatch\.python-anchor-failure-{suffix}$",
        kafka_poll_interval=timedelta(milliseconds=100),
    )
    return topic, TopicPartition(topic, 0), cfg


def _dispatch_payload(
    tenant: str,
    task_id: int,
    value: str,
    invocation_id: str,
    *,
    schema_version: str = "v2",
) -> dict[str, Any]:
    return {
        "schemaVersion": schema_version,
        "tenantId": tenant,
        "taskId": task_id,
        "workerType": "echo",
        "parameters": {"value": value},
        "runtimeAttributes": {"partitionInvocationId": invocation_id},
        "traceId": "trace-live-python",
    }


def _assert_live_results(
    claims: list[dict[str, Any]],
    reports: list[dict[str, Any]],
    tenant: str,
    worker_code: str,
) -> None:
    claims_by_task = {claim["_taskId"]: claim for claim in claims}
    reports_by_task = {report["taskId"]: report for report in reports}

    assert set(claims_by_task) == {9001, 9004}
    assert set(reports_by_task) == {9001, 9004}
    assert 9002 not in claims_by_task, "foreign-tenant record must never CLAIM"
    assert 9003 not in claims_by_task, "unknown-schema record must never CLAIM"

    first_claim = claims_by_task[9001]
    assert first_claim["tenantId"] == tenant
    assert first_claim["workerId"] == worker_code
    assert first_claim["partitionInvocationId"] == "pinv-live-1"

    first_report = reports_by_task[9001]
    assert first_report["tenantId"] == tenant
    assert first_report["workerId"] == worker_code
    assert first_report["success"] is True
    assert first_report["outputs"] == {"echo": "hello-live"}
    assert first_report["partitionInvocationId"] == "pinv-live-1"

    after_report = reports_by_task[9004]
    assert after_report["success"] is True
    assert after_report["outputs"] == {"echo": "after-withhold"}
    assert after_report["partitionInvocationId"] == "pinv-live-4"


@pytest.mark.skipif(
    not os.getenv("BATCH_SDK_KAFKA_BOOTSTRAP"),
    reason="BATCH_SDK_KAFKA_BOOTSTRAP not set; live broker integration skipped",
)
@pytest.mark.parametrize("blocked_schema", [False, True])
async def test_live_kafka_dispatch_claim_execute_report_against_fake_platform(
    blocked_schema: bool,
) -> None:
    bootstrap = os.environ["BATCH_SDK_KAFKA_BOOTSTRAP"]
    suffix = str(time.time_ns())
    tenant = "acme"
    worker_code = f"w-py-live-{suffix}"
    topic = f"batch.task.dispatch.{tenant}.echo-{suffix}"

    async with FakeBatchPlatform() as platform:
        cfg = BatchPlatformClientConfig(
            base_url=platform.base_url,
            api_key="test-api-key",
            tenant_id=tenant,
            worker_code=worker_code,
            max_concurrent_tasks=4,
            kafka_bootstrap=bootstrap,
            kafka_group_id=f"g-sdk-{tenant}-{worker_code}",
            kafka_topic_pattern=rf"^batch\.task\.dispatch\.{tenant}\.echo-{suffix}$",
            kafka_poll_interval=timedelta(milliseconds=100),
        )
        http = PlatformHttpClient(cfg)
        admin = AIOKafkaAdminClient(bootstrap_servers=bootstrap)
        producer = AIOKafkaProducer(bootstrap_servers=bootstrap)
        consumer: KafkaTaskConsumer | None = None
        try:
            await admin.start()
            # Apache Kafka 镜像默认可能关闭自动建 topic。显式建临时 topic,避免
            # 测试依赖 broker 的 auto.create.topics.enable 默认值或 metadata race。
            await admin.create_topics(
                [NewTopic(name=topic, num_partitions=2, replication_factor=1)]
            )
            await producer.start()

            dispatcher = TaskDispatcher(cfg, http, {"echo": EchoHandler()})
            consumer = KafkaTaskConsumer(cfg, dispatcher)
            await consumer.start()
            await _wait_until(lambda: len(consumer._consumer.assignment()) == 2)  # type: ignore[union-attr]

            payload = _dispatch_payload(tenant, 9001, "hello-live", "pinv-live-1")
            await producer.send_and_wait(topic, json.dumps(payload).encode("utf-8"), partition=1)

            # 异常消息暂扣分区 0, 另一个分区仍应正常处理。
            foreign = _dispatch_payload(
                "foreign-tenant", 9002, "must-not-run-foreign", "pinv-live-2"
            )
            unsupported = _dispatch_payload(
                tenant,
                9003,
                "must-not-run-v3",
                "pinv-live-3",
                schema_version="v3",
            )
            after_withhold = _dispatch_payload(tenant, 9004, "after-withhold", "pinv-live-4")
            message = unsupported if blocked_schema else foreign
            await producer.send_and_wait(topic, json.dumps(message).encode("utf-8"), partition=0)
            await _wait_until(lambda: TopicPartition(topic, 0) in consumer.withheld_ceilings)
            await producer.send_and_wait(
                topic, json.dumps(after_withhold).encode("utf-8"), partition=1
            )

            await _wait_until(lambda: len(platform.get_reports()) >= 2)
            await _wait_until(lambda: dispatcher.in_flight_count() == 0)

            _assert_live_results(platform.get_claims(), platform.get_reports(), tenant, worker_code)
            assert await consumer._consumer.committed(TopicPartition(topic, 0)) in (None, 0)  # type: ignore[union-attr]
            assert await consumer._consumer.committed(TopicPartition(topic, 1)) == 2  # type: ignore[union-attr]
        finally:
            if consumer is not None:
                await consumer.stop()
            await producer.stop()
            await admin.close()
            await http.close()


@pytest.mark.skipif(
    not os.getenv("BATCH_SDK_KAFKA_BOOTSTRAP"),
    reason="BATCH_SDK_KAFKA_BOOTSTRAP not set; live broker integration skipped",
)
async def test_live_reassigned_partition_recovers_after_other_member_commits() -> None:
    """真实消费组中, 暂扣分区转交后由另一成员提交, 再交回原成员继续提交。"""
    bootstrap = os.environ["BATCH_SDK_KAFKA_BOOTSTRAP"]
    suffix = str(time.time_ns())
    topic = f"batch.task.dispatch.python-transfer-{suffix}"
    cfg = BatchPlatformClientConfig(
        base_url="http://localhost:8081",
        tenant_id="acme",
        worker_code=f"python-transfer-{suffix}",
        max_concurrent_tasks=4,
        kafka_bootstrap=bootstrap,
        kafka_group_id=f"python-transfer-{suffix}",
        kafka_topic_pattern=rf"^batch\.task\.dispatch\.python-transfer-{suffix}$",
        kafka_poll_interval=timedelta(milliseconds=100),
    )
    dispatcher = MagicMock()
    dispatcher.in_flight_count.return_value = 0
    dispatcher.accepts_new_tasks.return_value = True
    admin = AIOKafkaAdminClient(bootstrap_servers=bootstrap)
    producer = AIOKafkaProducer(bootstrap_servers=bootstrap)
    first: KafkaTaskConsumer | None = None
    second: KafkaTaskConsumer | None = None

    async def first_handle(_tp: Any, rec: Any) -> DispatchDisposition:
        return DispatchDisposition.WITHHOLD if rec.offset == 0 else DispatchDisposition.ACCEPTED

    async def second_handle(_tp: Any, _rec: Any) -> DispatchDisposition:
        return DispatchDisposition.ACCEPTED

    try:
        await admin.start()
        await admin.create_topics([NewTopic(name=topic, num_partitions=2, replication_factor=1)])
        await producer.start()
        first = KafkaTaskConsumer(cfg, dispatcher)
        first._handle_record = first_handle  # type: ignore[method-assign]
        await first.start()
        await _wait_until(lambda: len(first._consumer.assignment()) == 2)  # type: ignore[union-attr]
        for partition in (0, 1):
            await producer.send_and_wait(topic, b"{}", partition=partition)
        await _wait_until(lambda: len(first.withheld_ceilings) == 2)
        for partition in (0, 1):
            await _wait_for_committed(first, TopicPartition(topic, partition), 0)

        second = KafkaTaskConsumer(cfg, dispatcher)
        second._handle_record = second_handle  # type: ignore[method-assign]
        await second.start()
        await _wait_until(lambda: len(second._consumer.assignment()) == 1)  # type: ignore[union-attr]
        transferred = next(iter(second._consumer.assignment()))  # type: ignore[union-attr]
        await _wait_for_committed(second, transferred, 1)
        assert transferred not in first.withheld_ceilings

        await second.stop()
        second = None
        await _wait_until(lambda: transferred in first._consumer.assignment())  # type: ignore[union-attr]
        await producer.send_and_wait(topic, b"{}", partition=transferred.partition)
        await _wait_for_committed(first, transferred, 2)
        assert transferred not in first.withheld_ceilings
    finally:
        if second is not None:
            await second.stop()
        if first is not None:
            await first.stop()
        await producer.stop()
        await admin.close()


@pytest.mark.skipif(
    not os.getenv("BATCH_SDK_KAFKA_BOOTSTRAP"),
    reason="BATCH_SDK_KAFKA_BOOTSTRAP not set; live broker integration skipped",
)
async def test_live_first_position_commit_failure_during_transfer_keeps_message() -> None:
    """首次保存失败后发生真实分区转交, 新成员仍应读到未处理消息。"""
    bootstrap = os.environ["BATCH_SDK_KAFKA_BOOTSTRAP"]
    topic, tp, cfg = _anchor_failure_fixture(bootstrap)
    dispatcher = MagicMock()
    dispatcher.in_flight_count.return_value = 0
    dispatcher.accepts_new_tasks.return_value = True
    admin = AIOKafkaAdminClient(bootstrap_servers=bootstrap)
    producer = AIOKafkaProducer(bootstrap_servers=bootstrap)
    first: KafkaTaskConsumer | None = None
    second: KafkaTaskConsumer | None = None
    entered_commit = asyncio.Event()
    release_failure = asyncio.Event()
    second_seen: list[int] = []
    first_handle = AsyncMock(return_value=DispatchDisposition.ACCEPTED)

    async def second_handle(_tp: Any, record: Any) -> DispatchDisposition:
        second_seen.append(record.offset)
        return DispatchDisposition.ACCEPTED

    try:
        await admin.start()
        await admin.create_topics([NewTopic(name=topic, num_partitions=1, replication_factor=1)])
        await producer.start()
        first = await _start_live_consumer(cfg, dispatcher, first_handle, tp)
        assert first._consumer is not None
        assert await first._consumer.position(tp) == 0

        async def fail_first_checkpoint(_offsets: Any) -> None:
            entered_commit.set()
            await release_failure.wait()
            raise RuntimeError("注入首次位置提交失败")

        first._consumer.commit = fail_first_checkpoint  # type: ignore[method-assign]
        await producer.send_and_wait(topic, b"first", partition=0)
        await asyncio.wait_for(entered_commit.wait(), timeout=20)
        first_handle.assert_not_awaited()
        assert await first._consumer.committed(tp) is None
        stopping = asyncio.create_task(first.stop())
        await _wait_until(lambda: not first._running)  # type: ignore[union-attr]
        release_failure.set()
        await stopping

        second = await _start_live_consumer(cfg, dispatcher, second_handle, tp)
        await second._consumer.position(tp)  # type: ignore[union-attr]
        await producer.send_and_wait(topic, b"marker", partition=0)
        await _wait_until(lambda: 1 in second_seen)
        assert 0 in second_seen, f"新成员跳过未处理的首条消息, 实际消费位置={second_seen}"
    finally:
        release_failure.set()
        if second is not None:
            await second.stop()
        if first is not None:
            await first.stop()
        await producer.stop()
        await admin.close()
