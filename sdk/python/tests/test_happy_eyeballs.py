"""验证 Python SDK 当前可安装依赖集合的 AnyIO Happy Eyeballs 行为。"""

from __future__ import annotations

import inspect
import json
import os
import socket
import time
from datetime import timedelta
from typing import Any

import anyio
import anyio._core._sockets as anyio_sockets
import pytest

from batch_worker_sdk import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import TransientError
from batch_worker_sdk.internal._http import PlatformHttpClient


class _FakeStream:
    async def aclose(self) -> None:
        return None


class _ControlledBackend:
    def __init__(self) -> None:
        self.attempts: list[tuple[str, float]] = []

    async def connect_tcp(
        self,
        host: str,
        port: int,
        local_address: object | None,
    ) -> _FakeStream:
        del port, local_address
        self.attempts.append((host, time.monotonic()))
        if ":" in host:
            await anyio.sleep_forever()
        return _FakeStream()


@pytest.mark.asyncio
async def test_anyio_happy_eyeballs_starts_ipv4_after_bounded_delay(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """IPv6 黑洞不能占满总连接超时,且成功后只保留一个连接。"""
    backend = _ControlledBackend()

    async def fake_getaddrinfo(*args: Any, **kwargs: Any) -> list[tuple[Any, ...]]:
        del args, kwargs
        return [
            (socket.AF_INET6, socket.SOCK_STREAM, 6, "", ("2001:db8::1", 443, 0, 0)),
            (socket.AF_INET, socket.SOCK_STREAM, 6, "", ("192.0.2.1", 443)),
        ]

    monkeypatch.setattr(anyio_sockets, "getaddrinfo", fake_getaddrinfo)
    monkeypatch.setattr(anyio_sockets, "get_async_backend", lambda: backend)

    started = time.monotonic()
    stream = await anyio_sockets.connect_tcp("dual-stack.test", 443)
    elapsed = time.monotonic() - started
    await stream.aclose()

    assert [host for host, _ in backend.attempts] == ["2001:db8::1", "192.0.2.1"]
    fallback_delay = backend.attempts[1][1] - backend.attempts[0][1]
    assert 0.20 <= fallback_delay < 1.0
    assert elapsed < 1.0


def test_anyio_happy_eyeballs_default_delay_matches_supported_dependency_contract() -> None:
    parameter = inspect.signature(anyio.connect_tcp).parameters["happy_eyeballs_delay"]

    assert parameter.default == 0.25


@pytest.mark.asyncio
async def test_real_socket_happy_eyeballs_matrix(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """真实 loopback socket 覆盖单栈、双栈、双向黑洞和全黑洞。"""
    matrix_file = os.getenv("BATCH_SDK_HE_MATRIX_FILE")
    if not matrix_file:
        pytest.skip("BATCH_SDK_HE_MATRIX_FILE not set")
    scenarios = json.loads(await anyio.Path(matrix_file).read_text(encoding="utf-8"))["scenarios"]
    original_getaddrinfo = anyio_sockets.getaddrinfo

    for scenario_name, scenario in scenarios.items():
        ordered_addresses = list(scenario["addresses"])

        async def ordered_getaddrinfo(
            *args: Any,
            _ordered_addresses: tuple[str, ...] = tuple(ordered_addresses),
            **kwargs: Any,
        ) -> list[tuple[Any, ...]]:
            host = args[0] if args else kwargs.get("host")
            port = args[1] if len(args) > 1 else kwargs.get("port")
            if host != "localhost":
                return await original_getaddrinfo(*args, **kwargs)
            records: list[tuple[Any, ...]] = []
            for address in _ordered_addresses:
                family = socket.AF_INET6 if ":" in address else socket.AF_INET
                socket_address: tuple[Any, ...]
                if family == socket.AF_INET6:
                    socket_address = (address, port, 0, 0)
                else:
                    socket_address = (address, port)
                records.append((family, socket.SOCK_STREAM, 6, "", socket_address))
            return records

        monkeypatch.setattr(anyio_sockets, "getaddrinfo", ordered_getaddrinfo)
        timeout = timedelta(milliseconds=int(scenario["timeout_ms"]))
        config = BatchPlatformClientConfig(
            base_url=scenario["base_url"],
            tenant_id="tx",
            worker_code="w-1",
            http_timeout=timeout,
        )
        client = PlatformHttpClient(config)
        started = time.monotonic()
        try:
            if scenario["expected"] == "success":
                await client.heartbeat("w-1", {"tenantId": "tx"})
            else:
                with pytest.raises(TransientError):
                    await client.heartbeat("w-1", {"tenantId": "tx"})
        finally:
            await client.close()
        elapsed = time.monotonic() - started
        assert elapsed < 2.5, scenario_name
        if scenario_name == "ipv6_blackhole":
            assert elapsed >= 0.15, scenario_name
