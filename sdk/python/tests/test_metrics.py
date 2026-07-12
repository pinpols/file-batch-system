"""client.metrics() / is_healthy() 快照 —— 对齐 Java SdkClientMetrics 语义。"""

from __future__ import annotations

from typing import Any

import pytest

from batch_worker_sdk import BatchPlatformClient, BatchPlatformClientConfig, SdkClientMetrics


def _cfg(max_concurrent: int = 4) -> BatchPlatformClientConfig:
    return BatchPlatformClientConfig(
        base_url="http://orch:8081",
        tenant_id="acme",
        worker_code="w-1",
        max_concurrent_tasks=max_concurrent,
    )


def _http_mock() -> Any:
    class _H:
        async def register(self, body: Any) -> Any: ...
        async def aclose(self) -> None: ...

    return _H()


class _Dispatcher:
    def __init__(self, *, fatal: bool = False, draining: bool = False, in_flight: int = 0) -> None:
        self._fatal, self._draining, self._n = fatal, draining, in_flight

    def in_flight_count(self) -> int:
        return self._n

    @property
    def is_fatal(self) -> bool:
        return self._fatal

    @property
    def is_draining(self) -> bool:
        return self._draining


class _Kafka:
    def __init__(self, *, crashed: bool = False) -> None:
        self._crashed = crashed

    @property
    def crashed(self) -> bool:
        return self._crashed


def _client_with(
    dispatcher: Any = None, kafka: Any = None, started: bool = True
) -> BatchPlatformClient:
    c = BatchPlatformClient(_cfg(), http=_http_mock())
    c.register_handler(_h("t-import"))
    c.register_handler(_h("t-export"))
    c._started = started
    c._dispatcher = dispatcher
    c._kafka = kafka
    return c


def _h(tt: str) -> Any:
    class _Handler:
        def task_type(self) -> str:
            return tt

        async def execute(self, ctx: Any) -> Any: ...

    return _Handler()


def test_metrics_fields_before_start_are_empty_but_typed() -> None:
    c = BatchPlatformClient(_cfg(max_concurrent=8), http=_http_mock())
    c.register_handler(_h("only"))
    m = c.metrics()
    assert isinstance(m, SdkClientMetrics)
    assert (m.tenant_id, m.worker_code) == ("acme", "w-1")
    assert m.started is False
    assert m.healthy is False
    assert m.in_flight_task_count == 0
    assert m.max_concurrent_tasks == 8
    assert m.registered_handler_count == 1
    assert m.kafka_consumer_lag == -1


def test_metrics_started_running_is_healthy() -> None:
    c = _client_with(_Dispatcher(in_flight=2), _Kafka())
    m = c.metrics()
    assert m.started
    assert m.healthy
    assert m.in_flight_task_count == 2
    assert m.registered_handler_count == 2
    assert not m.dispatcher_fatal
    assert not m.consumer_crashed
    assert c.is_healthy() is True


def test_dispatcher_fatal_is_unhealthy() -> None:
    c = _client_with(_Dispatcher(fatal=True), _Kafka())
    m = c.metrics()
    assert m.dispatcher_fatal is True
    assert m.healthy is False
    assert c.is_healthy() is False


def test_consumer_crashed_is_unhealthy() -> None:
    c = _client_with(_Dispatcher(), _Kafka(crashed=True))
    m = c.metrics()
    assert m.consumer_crashed is True
    assert m.healthy is False
    assert c.is_healthy() is False


def test_draining_is_still_healthy() -> None:
    # 优雅停机中(lease 仍续约)算健康 —— 对齐 Java isHealthy() drain=true。
    c = _client_with(_Dispatcher(draining=True), _Kafka())
    m = c.metrics()
    assert m.dispatcher_draining is True
    assert m.healthy is True
    assert c.is_healthy() is True


def test_metrics_snapshot_is_immutable() -> None:
    m = _client_with(_Dispatcher(), _Kafka()).metrics()
    with pytest.raises((AttributeError, TypeError)):
        m.healthy = False  # type: ignore[misc]
