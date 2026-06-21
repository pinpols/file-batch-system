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
        producer = AIOKafkaProducer(bootstrap_servers=bootstrap)
        consumer: KafkaTaskConsumer | None = None
        try:
            await producer.start()
            # 先写 seed 让 broker 创建 topic;consumer 使用 latest,后续只消费真正测试消息。
            await producer.send_and_wait(topic, b'{"seed":true}')
            await asyncio.sleep(1.0)

            dispatcher = TaskDispatcher(cfg, http, {"echo": EchoHandler()})
            consumer = KafkaTaskConsumer(cfg, dispatcher)
            await consumer.start()
            await asyncio.sleep(1.0)

            payload: dict[str, Any] = {
                "schemaVersion": "v2",
                "tenantId": tenant,
                "taskId": 9001,
                "workerType": "echo",
                "parameters": {"value": "hello-live"},
                "runtimeAttributes": {"partitionInvocationId": "pinv-live-1"},
                "traceId": "trace-live-python",
            }
            await producer.send_and_wait(topic, json.dumps(payload).encode("utf-8"))

            await _wait_until(lambda: len(platform.get_reports()) >= 1)
            await _wait_until(lambda: dispatcher.in_flight_count() == 0)

            claims = platform.get_claims()
            reports = platform.get_reports()
            assert claims, "dispatcher must CLAIM before handler execution"
            assert claims[0]["tenantId"] == tenant
            assert claims[0]["workerId"] == worker_code
            assert claims[0]["partitionInvocationId"] == "pinv-live-1"

            report = reports[0]
            assert report["tenantId"] == tenant
            assert report["workerId"] == worker_code
            assert report["success"] is True
            assert report["outputs"] == {"echo": "hello-live"}
            assert report["partitionInvocationId"] == "pinv-live-1"
        finally:
            if consumer is not None:
                await consumer.stop()
            await producer.stop()
            await http.close()
