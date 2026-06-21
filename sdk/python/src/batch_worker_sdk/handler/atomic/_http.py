"""HTTP atomic handler —— Java ``HttpAtomicHandler`` 的异步移植。

基于 :mod:`httpx`(已是 SDK 依赖,平台 client 在
:mod:`batch_worker_sdk.internal._http` 已经用了)发起单次 HTTP 调用。
SSRF 防护(拦截 private / loopback / link-local / site-local + 主机黑名单)
在请求**发出前**执行,与 Java JDK ``HttpClient`` 实现一致。

与 Java ``handler/atomic/HttpAtomicHandler.java`` 对齐 ——
参数 / 输出 / config 形态 1:1。
"""

from __future__ import annotations

import ipaddress
import logging
import re
import socket
from dataclasses import dataclass, field
from typing import Any
from urllib.parse import urlparse

import httpx

from batch_worker_sdk.handler._atomic import SdkAbstractAtomicHandler
from batch_worker_sdk.task.context import SdkTaskContext

_LOG = logging.getLogger(__name__)

_DEFAULT_METHODS: frozenset[str] = frozenset({"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD"})


@dataclass(frozen=True)
class HttpAtomicConfig:
    """:class:`HttpAtomicHandler` 的配置(对齐 Java ``HttpAtomicConfig``)。

    Attributes:
        task_type: 注册的任务类型码。
        block_private_ips: 拒绝 private/loopback/link-local/site-local IP(SSRF)。
        blocked_host_patterns: 额外的主机黑名单(子串或正则)。
        allowed_methods: 允许的 HTTP method(大写)。
        timeout_seconds: 单次请求的 connect + read 超时。
        max_response_bytes: 响应体字节上限,超出部分截断。
    """

    task_type: str
    block_private_ips: bool = True
    blocked_host_patterns: frozenset[str] = field(default_factory=frozenset)
    allowed_methods: frozenset[str] = field(default_factory=lambda: _DEFAULT_METHODS)
    timeout_seconds: int = 30
    max_response_bytes: int = 1024 * 1024

    def __post_init__(self) -> None:
        if not self.task_type:
            raise ValueError("task_type must not be blank")
        if self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be > 0")
        if self.max_response_bytes <= 0:
            raise ValueError("max_response_bytes must be > 0")
        # 归一化:大写 method 的 frozenset;空集默认回退。
        methods = frozenset(m.upper() for m in self.allowed_methods) or _DEFAULT_METHODS
        object.__setattr__(self, "allowed_methods", methods)
        object.__setattr__(self, "blocked_host_patterns", frozenset(self.blocked_host_patterns))

    @classmethod
    def defaults(cls, task_type: str) -> HttpAtomicConfig:
        """默认值:拦截 private IP、放行所有标准 method、30s 超时、1 MiB 上限。"""
        return cls(task_type=task_type)


class HttpAtomicHandler(SdkAbstractAtomicHandler):
    """开箱即用的 HTTP atomic handler。

    参数(来自 ``ctx.parameters``):

    * ``url`` (str,必填)
    * ``method`` (str,默认 ``"GET"``)
    * ``headers`` (dict[str, str],可选)
    * ``body`` (str,可选)

    输出 dict:
    ``{"statusCode": int, "responseBody": str, "responseTruncated": bool}``。
    非 2xx **不**视为错误 —— 状态码原样返回交由调用方判定
    (与 Java 行为一致)。
    """

    def __init__(
        self,
        config: HttpAtomicConfig,
        *,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if config is None:
            raise ValueError("config must not be None")
        self._config = config
        # 允许测试注入;否则每次请求用 context manager 自建,
        # 单次调用之间相互隔离,超时也可独立调整。
        self._client = client

    def task_type(self) -> str:
        return self._config.task_type

    async def _do_invoke(self, ctx: SdkTaskContext) -> dict[str, Any]:
        params = ctx.parameters
        url = params.get("url")
        if not isinstance(url, str) or not url.strip():
            raise ValueError("missing required parameter: url")
        method = str(params.get("method") or "GET").upper()
        headers_raw = params.get("headers") or {}
        if not isinstance(headers_raw, dict):
            raise ValueError("parameter 'headers' must be a dict[str, str]")
        headers = {str(k): str(v) for k, v in headers_raw.items()}
        body = params.get("body")
        if body is not None and not isinstance(body, (str, bytes)):
            body = str(body)

        if method not in self._config.allowed_methods:
            raise ValueError(f"HTTP method not allowed: {method}")

        parsed = urlparse(url)
        host = parsed.hostname
        if not host:
            raise ValueError(f"invalid url, no host: {url}")

        self._check_ssrf(host)

        timeout = httpx.Timeout(self._config.timeout_seconds)
        client = self._client
        owns_client = client is None
        if owns_client:
            client = httpx.AsyncClient(timeout=timeout)
        assert client is not None
        try:
            response = await client.request(
                method,
                url,
                headers=headers or None,
                content=body,
                timeout=timeout,
            )
            raw = response.content or b""
            truncated = len(raw) > self._config.max_response_bytes
            if truncated:
                raw = raw[: self._config.max_response_bytes]
            return {
                "statusCode": response.status_code,
                "responseBody": raw.decode("utf-8", errors="replace"),
                "responseTruncated": truncated,
            }
        finally:
            if owns_client:
                await client.aclose()

    # ── SSRF 防护 ──────────────────────────────────────────────────────────

    def _check_ssrf(self, host: str) -> None:
        if self._config.block_private_ips:
            for ip in _resolve_addresses(host):
                if (
                    ip.is_loopback
                    or ip.is_link_local
                    or ip.is_private
                    or ip.is_unspecified
                    or ip.is_reserved
                ):
                    raise PermissionError(
                        f"SSRF blocked: private/loopback IP for host {host} ({ip})"
                    )
        for pattern in self._config.blocked_host_patterns:
            if pattern in host or _matches_regex(host, pattern):
                raise PermissionError(f"SSRF blocked: host matches blocked pattern {pattern}")


_IpAddr = ipaddress.IPv4Address | ipaddress.IPv6Address


def _resolve_addresses(host: str) -> list[_IpAddr]:
    """把 ``host`` 解析成所有地址;若本身就是字面量 IP,直接用。"""
    try:
        return [ipaddress.ip_address(host)]
    except ValueError:
        pass
    try:
        infos = socket.getaddrinfo(host, None)
    except OSError as exc:
        raise PermissionError(f"SSRF check: unable to resolve host {host}: {exc}") from exc
    out: list[_IpAddr] = []
    for info in infos:
        sockaddr = info[4]
        try:
            out.append(ipaddress.ip_address(sockaddr[0]))
        except ValueError:
            continue
    return out


def _matches_regex(host: str, pattern: str) -> bool:
    try:
        return re.fullmatch(pattern, host) is not None
    except re.error:
        return False
