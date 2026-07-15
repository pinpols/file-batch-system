"""Live Kafka + HTTP fake 接通测试。

默认无 ``KAFKA_BOOTSTRAP`` 时跳过;CI 的 sdk live gate 会启动真实 broker 后
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

import pytest
from aiokafka import AIOKafkaProducer
from aiokafka.admin import AIOKafkaAdminClient, NewTopic

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher
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
    not os.getenv("KAFKA_BOOTSTRAP"),
    reason="KAFKA_BOOTSTRAP not set; live broker integration skipped",
)
async def test_live_kafka_dispatch_claim_execute_report_against_fake_platform() -> None:
    bootstrap = os.environ["KAFKA_BOOTSTRAP"]
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
            kafka_topic_pattern=rf"^batch\.task\.dispatch\.{tenant}\..+$",
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
                [NewTopic(name=topic, num_partitions=1, replication_factor=1)]
            )
            await producer.start()

            dispatcher = TaskDispatcher(cfg, http, {"echo": EchoHandler()})
            consumer = KafkaTaskConsumer(cfg, dispatcher)
            await consumer.start()
            await asyncio.sleep(1.0)

            payload = _dispatch_payload(tenant, 9001, "hello-live", "pinv-live-1")
            await producer.send_and_wait(topic, json.dumps(payload).encode("utf-8"))

            # 横向对齐 Go / TS #826 / Rust:同一分区在正常消息之后连续遇到
            # foreign-tenant 与未知 schema 大版本时,两条都必须 withhold(不提交),
            # 但不能 pause 分区。最后一条本租户 v2 任务必须继续到达 handler。
            # topic 默认单分区且 send_and_wait 串行,因此顺序稳定。
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
            for message in (foreign, unsupported, after_withhold):
                await producer.send_and_wait(topic, json.dumps(message).encode("utf-8"))

            await _wait_until(lambda: len(platform.get_reports()) >= 2)
            await _wait_until(lambda: dispatcher.in_flight_count() == 0)

            _assert_live_results(platform.get_claims(), platform.get_reports(), tenant, worker_code)
        finally:
            if consumer is not None:
                await consumer.stop()
            await producer.stop()
            await admin.close()
            await http.close()
