""":class:`HttpDispatchHandler` 的测试 —— fan-out / 部分失败 / 全失败 / SSRF。"""

from __future__ import annotations

from typing import Any

import httpx
import pytest

from batch_worker_sdk.handler.builtin import (
    HttpDispatchConfig,
    HttpDispatchHandler,
    HttpDispatchTarget,
)
from batch_worker_sdk.task.context import SdkTaskContext


def _ctx(targets: list[dict[str, Any]] | None = None) -> SdkTaskContext:
    params: dict[str, Any] = {}
    if targets is not None:
        params["targets"] = targets
    return SdkTaskContext(
        tenant_id="t1",
        task_id=2,
        worker_code="w1",
        task_type="dispatch_http",
        parameters=params,
    )


def _make_handler(
    config: HttpDispatchConfig,
    responder: httpx.MockTransport,
) -> HttpDispatchHandler:
    """构造一个接到内存 MockTransport 的 handler,让测试完全离线运行。"""
    client = httpx.AsyncClient(transport=responder, timeout=config.timeout_seconds)
    return HttpDispatchHandler(config, client=client)


@pytest.mark.asyncio
async def test_dispatch_three_targets_all_succeed() -> None:
    seen_urls: list[str] = []

    def _handle(req: httpx.Request) -> httpx.Response:
        seen_urls.append(str(req.url))
        return httpx.Response(200, json={"ok": True})

    cfg = HttpDispatchConfig(task_type="dispatch_http", block_private_ips=False)
    handler = _make_handler(cfg, httpx.MockTransport(_handle))

    targets = [
        {"url": "https://api.example.com/a"},
        {"url": "https://api.example.com/b"},
        {"url": "https://api.example.com/c"},
    ]
    result = await handler.execute(_ctx(targets))

    assert result.success is True
    assert result.output["success"] == 3
    assert "failed" not in result.output
    assert len(seen_urls) == 3


@pytest.mark.asyncio
async def test_dispatch_partial_failure_counts_both() -> None:
    def _handle(req: httpx.Request) -> httpx.Response:
        if req.url.path.endswith("/b"):
            return httpx.Response(500, json={"error": "boom"})
        return httpx.Response(200, json={"ok": True})

    cfg = HttpDispatchConfig(task_type="dispatch_http", block_private_ips=False)
    handler = _make_handler(cfg, httpx.MockTransport(_handle))

    targets = [
        {"url": "https://api.example.com/a"},
        {"url": "https://api.example.com/b"},
        {"url": "https://api.example.com/c"},
    ]
    result = await handler.execute(_ctx(targets))

    assert result.success is True  # 没开 fail-fast → 仍算 success
    assert result.output["success"] == 2
    assert result.output["failed"] == 1


@pytest.mark.asyncio
async def test_dispatch_all_fail_with_fail_fast_returns_failure() -> None:
    def _handle(req: httpx.Request) -> httpx.Response:
        return httpx.Response(500)

    cfg = HttpDispatchConfig(
        task_type="dispatch_http",
        block_private_ips=False,
        fail_fast=True,
        concurrency=1,  # 串行执行,让 abort 短路在 3 个全发之前
    )
    handler = _make_handler(cfg, httpx.MockTransport(_handle))

    targets = [
        {"url": "https://api.example.com/a"},
        {"url": "https://api.example.com/b"},
        {"url": "https://api.example.com/c"},
    ]
    result = await handler.execute(_ctx(targets))

    assert result.success is False
    assert result.output["errorCode"] == "DISPATCH_FAILED"


@pytest.mark.asyncio
async def test_dispatch_empty_targets_returns_success_with_zero() -> None:
    def _handle(req: httpx.Request) -> httpx.Response:
        return httpx.Response(200)

    cfg = HttpDispatchConfig(task_type="dispatch_http", block_private_ips=False)
    handler = _make_handler(cfg, httpx.MockTransport(_handle))

    result = await handler.execute(_ctx([]))

    assert result.success is True
    assert result.output["success"] == 0


@pytest.mark.asyncio
async def test_ssrf_loopback_blocked_by_default() -> None:
    def _handle(req: httpx.Request) -> httpx.Response:
        return httpx.Response(200)

    cfg = HttpDispatchConfig(task_type="dispatch_http")  # block_private_ips 默认 True
    handler = _make_handler(cfg, httpx.MockTransport(_handle))

    targets = [{"url": "http://127.0.0.1:8080/x"}]
    result = await handler.execute(_ctx(targets))

    assert result.success is False
    assert result.output["errorCode"] == "DISPATCH_SSRF_BLOCKED"


def test_http_dispatch_target_validates() -> None:
    t = HttpDispatchTarget(url="https://example.com/x", method="PUT")
    assert t.method == "PUT"
