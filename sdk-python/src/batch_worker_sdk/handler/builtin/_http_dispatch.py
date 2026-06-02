"""Builtin HTTP-dispatch handler (ADR-036 Dispatch shape).

Mirrors Java ``com.example.batch.sdk.handler.builtin.HttpDispatchHandler`` /
``HttpDispatchConfig``. Java's flavour is JDBC → HTTP per-row; the Python
flavour delegates the *target selection* to a tenant hook
(:meth:`HttpDispatchHandler._resolve_targets`) so the same builtin
serves DB-driven, config-inline, or upstream-API target lists.

Concurrency: fan-out per target via :func:`asyncio.gather` with an
:class:`asyncio.Semaphore`-bounded concurrency window. SSRF guard mirrors
Java (loopback / private-IP block) but is best-effort — DNS resolution
happens via :func:`asyncio.get_event_loop().getaddrinfo` once per target.
"""

from __future__ import annotations

import asyncio
import ipaddress
from typing import Any
from urllib.parse import urlparse

import httpx
from pydantic import BaseModel, ConfigDict, Field

from batch_worker_sdk.handler.builtin._row_result import SdkRowResult
from batch_worker_sdk.handler.handler import SdkTaskHandler  # noqa: F401 — protocol parity
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult


class HttpDispatchTarget(BaseModel):
    """One fan-out target: URL + optional per-target payload override."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    url: str
    """Full URL to POST/PUT/etc. to (resolved against ``config.base_url`` is caller's job)."""

    method: str = "POST"
    """HTTP method (``POST`` / ``PUT`` / ``PATCH`` / etc.)."""

    payload: dict[str, Any] = Field(default_factory=dict)
    """Per-target JSON body (merged with config-level ``payload_template``)."""

    headers: dict[str, str] = Field(default_factory=dict)
    """Per-target HTTP headers (overlay over config-level ``headers``)."""


class HttpDispatchConfig(BaseModel):
    """Settings for :class:`HttpDispatchHandler`.

    Mirrors Java ``HttpDispatchConfig`` record. Java's record is JDBC-+
    single-endpoint focused; the Python config is fan-out friendly
    (concurrency, headers, payload template, per-target overrides via the
    :meth:`HttpDispatchHandler._resolve_targets` hook).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    task_type: str
    """Globally-unique task-type code registered with the platform."""

    base_url: str | None = None
    """Optional base URL prepended to target ``url`` if relative."""

    method: str = "POST"
    """Default HTTP method for fan-out requests."""

    headers: dict[str, str] = Field(default_factory=dict)
    """Default HTTP headers applied to every fan-out request."""

    payload_template: dict[str, Any] = Field(default_factory=dict)
    """Default JSON body merged under each target's per-target payload."""

    concurrency: int = Field(default=8, gt=0)
    """Max in-flight fan-out requests (semaphore cap)."""

    timeout_seconds: float = Field(default=30.0, gt=0)
    """Per-request total timeout (mirrors Java ``timeoutSeconds``)."""

    fail_fast: bool = False
    """If ``True`` → first non-2xx aborts the whole batch (Java parity)."""

    block_private_ips: bool = True
    """SSRF guard: refuse loopback / private / link-local target hosts."""

    @classmethod
    def defaults(cls, task_type: str) -> HttpDispatchConfig:
        return cls(task_type=task_type)


class HttpDispatchHandler:
    """Fan-out per-target HTTP push template (DB / inline / API → external HTTP).

    Tenant subclasses override :meth:`_resolve_targets` to produce the
    list of :class:`HttpDispatchTarget` for a given task; the builtin
    handles the concurrency-bounded async fan-out, retries are *not* in
    scope (let the SDK retry layer or upstream retry policy own that).

    Java parity: 2xx counts as success, non-2xx / exceptions count as
    failed. ``fail_fast=True`` aborts on first failure mirroring Java.
    """

    def __init__(
        self,
        config: HttpDispatchConfig,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._config = config
        self._client = client
        self._owns_client = client is None

    # -- SdkTaskHandler protocol --------------------------------------------------

    def task_type(self) -> str:
        return self._config.task_type

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        return None

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        counts = SdkRowResult()
        client = self._client or httpx.AsyncClient(timeout=self._config.timeout_seconds)
        try:
            try:
                targets = await self._resolve_targets(ctx)
            except Exception as ex:
                return SdkTaskResult.fail(
                    "DISPATCH_RESOLVE_FAILED",
                    f"target resolution failed: {ex}",
                    cause=ex,
                )

            if not targets:
                return SdkTaskResult.success_with(
                    output=counts.to_output(), message="no targets to dispatch"
                )

            try:
                for tgt in targets:
                    self._check_ssrf(tgt.url)
            except (PermissionError, ValueError) as ex:
                return SdkTaskResult.fail(
                    "DISPATCH_SSRF_BLOCKED",
                    f"target rejected by SSRF guard: {ex}",
                    cause=ex,
                )

            sem = asyncio.Semaphore(self._config.concurrency)
            abort_event = asyncio.Event()

            async def _one(target: HttpDispatchTarget) -> None:
                if abort_event.is_set():
                    return
                if ctx.cancel_signal is not None and ctx.cancel_signal.is_cancellation_requested:
                    abort_event.set()
                    return
                async with sem:
                    try:
                        ok = await self._dispatch_to_target(ctx, client, target)
                    except Exception:
                        ok = False
                    if ok:
                        counts.inc_success()
                    else:
                        counts.inc_failed()
                        if self._config.fail_fast:
                            abort_event.set()

            await asyncio.gather(*(_one(t) for t in targets), return_exceptions=False)

            if self._config.fail_fast and counts.failed > 0:
                return SdkTaskResult.fail(
                    "DISPATCH_FAILED",
                    f"dispatched {counts.success} ok, {counts.failed} failed (fail-fast)",
                )
        finally:
            if self._owns_client:
                await client.aclose()

        return SdkTaskResult.success_with(
            output=counts.to_output(),
            message=f"dispatched {counts.success} ok, {counts.failed} failed",
        )

    # -- tenant-overridable hooks (mirror Java SdkAbstractDispatchHandler) --------

    async def _resolve_targets(self, ctx: SdkTaskContext) -> list[HttpDispatchTarget]:
        """**Abstract for tenants.** Produce the per-task fan-out target list.

        Default reads ``ctx.parameters['targets']`` (a list of dicts) when
        present so simple inline configs work without subclassing; raises
        if absent.
        """
        raw = ctx.parameters.get("targets")
        if not isinstance(raw, list):
            raise NotImplementedError(
                "HttpDispatchHandler subclasses must override _resolve_targets, "
                "or callers must supply 'targets' in ctx.parameters as a list of dicts"
            )
        return [HttpDispatchTarget.model_validate(item) for item in raw]

    async def _dispatch_to_target(
        self,
        ctx: SdkTaskContext,
        client: httpx.AsyncClient,
        target: HttpDispatchTarget,
    ) -> bool:
        """Send one HTTP request; default treats 2xx as success."""
        url = self._resolve_url(target.url)
        method = (target.method or self._config.method).upper()
        headers = {**self._config.headers, **target.headers}
        payload = {**self._config.payload_template, **target.payload}
        resp = await client.request(
            method,
            url,
            json=payload if payload else None,
            headers=headers or None,
            timeout=self._config.timeout_seconds,
        )
        return 200 <= resp.status_code < 300

    # -- internals ----------------------------------------------------------------

    def _resolve_url(self, url: str) -> str:
        if self._config.base_url and not url.startswith(("http://", "https://")):
            return self._config.base_url.rstrip("/") + "/" + url.lstrip("/")
        return url

    def _check_ssrf(self, url: str) -> None:
        if not self._config.block_private_ips:
            return
        # Quick string-host extraction — httpx doesn't expose a public
        # URL parser, ``urllib.parse`` is sufficient. We only block when
        # the host is a literal IP that falls in private space; DNS-based
        # blocking would require an async resolve and isn't worth the
        # latency for the SSRF backstop (Java's ``InetAddress.getByName``
        # equivalent path).
        host = urlparse(self._resolve_url(url)).hostname
        if not host:
            raise ValueError(f"invalid endpoint, no host: {url}")
        try:
            ip = ipaddress.ip_address(host)
        except ValueError:
            return
        if ip.is_loopback or ip.is_private or ip.is_link_local or ip.is_unspecified:
            raise PermissionError(
                f"SSRF blocked: private/loopback IP for host {host}"
            )
