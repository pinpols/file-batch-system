"""Lane C —— KafkaTaskConsumer.start() SASL fail-fast 行为。

aiokafka 在 SASL 凭据错 / broker 不可达时会无限 retry,没有内置 timeout。
本测试覆盖 ``KAFKA_START_TIMEOUT_S`` 包装的 ``asyncio.wait_for``:

- ``test_start_raises_platform_error_when_underlying_start_hangs``
  模拟 aiokafka start 永不返回,断言抛 ``PlatformError(code='kafka_start_timeout')``,
  且耗时受限于 timeout 常量,**不会**hang 直到 K8s liveness 回退。
- ``test_start_succeeds_when_underlying_start_quick``
  正常路径(start 立即返回)下不抛、不 timeout、_running=True。
"""

from __future__ import annotations

import asyncio
import time
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from batch_worker_sdk import BatchPlatformClientConfig, TaskDispatcher
from batch_worker_sdk.exceptions import PlatformError
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.internal._kafka import KAFKA_START_TIMEOUT_S, KafkaTaskConsumer


def _cfg() -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url="http://orch:8081",
        tenant_id="acme",
        worker_code="w-1",
        max_concurrent_tasks=4,
        kafka_bootstrap="kafka:9092",
        kafka_group_id="g-1",
        kafka_topic_pattern="batch.task.dispatch.acme.*",
    )


async def _make_consumer(mock_consumer: Any) -> tuple[KafkaTaskConsumer, PlatformHttpClient]:
    cfg = _cfg()
    http = PlatformHttpClient(cfg)
    dispatcher = TaskDispatcher(cfg, http)
    consumer = KafkaTaskConsumer(cfg, dispatcher, consumer=mock_consumer)
    return consumer, http


async def test_start_raises_platform_error_when_underlying_start_hangs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """aiokafka.start() 永不返回 → 包装抛 PlatformError(code=kafka_start_timeout)。"""
    # arrange: 把全局 timeout 临时调小,避免测试套件等满 10s。
    monkeypatch.setattr("batch_worker_sdk.internal._kafka.KAFKA_START_TIMEOUT_S", 0.2)
    mock_consumer = MagicMock()

    async def _never_returns() -> None:
        await asyncio.sleep(999)

    mock_consumer.start = AsyncMock(side_effect=_never_returns)
    consumer, http = await _make_consumer(mock_consumer)
    try:
        # act + assert: PlatformError 抛出且耗时不超过 timeout + 富余。
        start_ts = time.monotonic()
        with pytest.raises(PlatformError) as exc_info:
            await consumer.start()
        elapsed = time.monotonic() - start_ts

        assert exc_info.value.code == "kafka_start_timeout"
        # 错误信息提示 SASL / broker,便于运维定位。
        assert "SASL" in exc_info.value.message or "broker" in exc_info.value.message
        # fail-fast 体现:远短于 K8s liveness(30s+)的默认窗口。
        assert elapsed < 2.0
        # _running 必须保持 False,后续 stop() 才能幂等短路。
        assert consumer.running is False
    finally:
        await http.close()


async def test_start_succeeds_when_underlying_start_quick() -> None:
    """正常路径:aiokafka.start() 立即返回 → 不抛、_running=True、subscribe 已调用。"""
    mock_consumer = MagicMock()
    mock_consumer.start = AsyncMock(return_value=None)
    # subscribe 是同步方法,在 _subscribe() 里调用。
    mock_consumer.subscribe = MagicMock()
    # stop() 会 await consumer.stop()(在 owns_consumer=False 时不调,但保险起见)。
    mock_consumer.stop = AsyncMock(return_value=None)

    consumer, http = await _make_consumer(mock_consumer)
    try:
        await consumer.start()
        assert consumer.running is True
        mock_consumer.start.assert_awaited_once()
        mock_consumer.subscribe.assert_called_once()
    finally:
        # 干净收尾:停掉后台 poll task,getmany 已被 mock 但返回值 None 会触发 TypeError;
        # 这里直接 stop(),内部 cancel poll task 即可。
        await consumer.stop()
        await http.close()


def test_kafka_start_timeout_constant_is_conservative() -> None:
    """timeout 常量必须 (a) 大于典型 SASL handshake (<2s),(b) 小于 K8s 默认 livenessProbe 阈值。"""
    assert KAFKA_START_TIMEOUT_S >= 5.0
    assert KAFKA_START_TIMEOUT_S < 30.0
