"""Unit tests for :mod:`batch_worker_sdk.handler.atomic._http`."""

from __future__ import annotations

import httpx
import pytest

from batch_worker_sdk.handler.atomic import HttpAtomicConfig, HttpAtomicHandler
from batch_worker_sdk.testkit import make_test_context

pytestmark = pytest.mark.asyncio


def _client_with(handler: httpx.MockTransport) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=handler)


async def test_http_happy_path_returns_status_and_body() -> None:
    async def respond(request: httpx.Request) -> httpx.Response:
        assert request.method == "GET"
        assert str(request.url) == "https://api.example.com/v1/ping"
        return httpx.Response(200, content=b"pong")

    config = HttpAtomicConfig(task_type="http", block_private_ips=False)
    handler = HttpAtomicHandler(config, client=_client_with(httpx.MockTransport(respond)))
    ctx = make_test_context(parameters={"url": "https://api.example.com/v1/ping"})

    result = await handler._do_invoke(ctx)

    assert result == {
        "statusCode": 200,
        "responseBody": "pong",
        "responseTruncated": False,
    }


async def test_http_truncates_oversized_body() -> None:
    async def respond(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"x" * 100)

    config = HttpAtomicConfig(task_type="http", block_private_ips=False, max_response_bytes=10)
    handler = HttpAtomicHandler(config, client=_client_with(httpx.MockTransport(respond)))
    ctx = make_test_context(parameters={"url": "https://api.example.com/big"})

    result = await handler._do_invoke(ctx)

    assert result["responseTruncated"] is True
    assert result["responseBody"] == "x" * 10


async def test_http_rejects_method_outside_allowlist() -> None:
    config = HttpAtomicConfig(
        task_type="http",
        block_private_ips=False,
        allowed_methods=frozenset({"GET"}),
    )
    handler = HttpAtomicHandler(
        config, client=_client_with(httpx.MockTransport(lambda r: httpx.Response(200)))
    )
    ctx = make_test_context(parameters={"url": "https://api.example.com", "method": "DELETE"})

    with pytest.raises(ValueError, match="HTTP method not allowed: DELETE"):
        await handler._do_invoke(ctx)


async def test_http_ssrf_blocks_loopback_literal_ip() -> None:
    config = HttpAtomicConfig(task_type="http")  # block_private_ips defaults to True
    handler = HttpAtomicHandler(
        config, client=_client_with(httpx.MockTransport(lambda r: httpx.Response(200)))
    )
    ctx = make_test_context(parameters={"url": "http://127.0.0.1/secret"})

    with pytest.raises(PermissionError, match="SSRF blocked"):
        await handler._do_invoke(ctx)


async def test_http_ssrf_blocks_host_pattern() -> None:
    config = HttpAtomicConfig(
        task_type="http",
        block_private_ips=False,
        blocked_host_patterns=frozenset({"internal"}),
    )
    handler = HttpAtomicHandler(
        config, client=_client_with(httpx.MockTransport(lambda r: httpx.Response(200)))
    )
    ctx = make_test_context(parameters={"url": "http://api.internal.example.com"})

    with pytest.raises(PermissionError, match="blocked pattern"):
        await handler._do_invoke(ctx)


async def test_http_missing_url_raises() -> None:
    config = HttpAtomicConfig(task_type="http", block_private_ips=False)
    handler = HttpAtomicHandler(
        config, client=_client_with(httpx.MockTransport(lambda r: httpx.Response(200)))
    )
    ctx = make_test_context(parameters={})

    with pytest.raises(ValueError, match="missing required parameter: url"):
        await handler._do_invoke(ctx)


async def test_http_config_defaults() -> None:
    cfg = HttpAtomicConfig.defaults("foo")
    assert cfg.task_type == "foo"
    assert cfg.block_private_ips is True
    assert "GET" in cfg.allowed_methods
    assert cfg.timeout_seconds == 30
