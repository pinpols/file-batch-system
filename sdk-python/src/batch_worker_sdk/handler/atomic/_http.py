"""HTTP atomic handler — async port of Java ``HttpAtomicHandler``.

Single HTTP call on top of :mod:`httpx` (already a SDK dep, used by
:mod:`batch_worker_sdk.internal._http` for the platform client). SSRF
defence (block private / loopback / link-local / site-local + host
deny-list) runs **before** the request is dispatched, mirroring the
Java JDK ``HttpClient`` implementation.

Aligns with Java ``handler/atomic/HttpAtomicHandler.java`` —
parameters / output / config shape are 1:1.
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
    """Config for :class:`HttpAtomicHandler` (mirrors Java ``HttpAtomicConfig``).

    Attributes:
        task_type: Registered task-type code.
        block_private_ips: Reject private/loopback/link-local/site-local IPs (SSRF).
        blocked_host_patterns: Extra host deny-list (substring or regex).
        allowed_methods: Allowed HTTP methods (upper-case).
        timeout_seconds: Per-request connect + read timeout.
        max_response_bytes: Response body byte cap; excess is truncated.
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
        # Normalize: frozenset of upper-case methods; empty → defaults.
        methods = frozenset(m.upper() for m in self.allowed_methods) or _DEFAULT_METHODS
        object.__setattr__(self, "allowed_methods", methods)
        object.__setattr__(self, "blocked_host_patterns", frozenset(self.blocked_host_patterns))

    @classmethod
    def defaults(cls, task_type: str) -> HttpAtomicConfig:
        """Defaults: block private IPs, all standard methods, 30s, 1 MiB."""
        return cls(task_type=task_type)


class HttpAtomicHandler(SdkAbstractAtomicHandler):
    """Out-of-the-box HTTP atomic handler.

    Parameters (from ``ctx.parameters``):

    * ``url`` (str, required)
    * ``method`` (str, default ``"GET"``)
    * ``headers`` (dict[str, str], optional)
    * ``body`` (str, optional)

    Output dict:
    ``{"statusCode": int, "responseBody": str, "responseTruncated": bool}``.
    Non-2xx is **not** an error — the status code is returned for the
    caller to interpret (matches Java behaviour).
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
        # Allow test injection; otherwise build per-request via a context
        # manager so each invocation is isolated and timeouts can vary.
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

    # ── SSRF defence ───────────────────────────────────────────────────────

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
                raise PermissionError(
                    f"SSRF blocked: host matches blocked pattern {pattern}"
                )


_IpAddr = ipaddress.IPv4Address | ipaddress.IPv6Address


def _resolve_addresses(host: str) -> list[_IpAddr]:
    """Resolve ``host`` to all addrs; if literal IP, use as-is."""
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
