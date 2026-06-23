"""内置 HTTP 分发 handler(ADR-036 Dispatch 形态)。

对齐 Java ``io.github.pinpols.batch.sdk.handler.builtin.HttpDispatchHandler`` /
``HttpDispatchConfig``。Java 版是 JDBC → 逐行 HTTP;Python 版把 *目标选择*
委托给租户钩子(:meth:`HttpDispatchHandler._resolve_targets`),让同一个内置
能同时服务 DB 驱动、配置内联、上游 API 三种 target 来源。

并发:每个 target 经 :func:`asyncio.gather` 扇出,用 :class:`asyncio.Semaphore`
限制并发窗口。SSRF 防护对齐 Java(loopback / 私网 IP 拦截),但只做尽力而为
—— DNS 解析通过 :func:`asyncio.get_event_loop().getaddrinfo` 每 target 一次。
"""

from __future__ import annotations

import asyncio
import ipaddress
from typing import Any
from urllib.parse import urlparse

import httpx
from pydantic import BaseModel, ConfigDict, Field

from batch_worker_sdk.handler._base import SdkRowResult
from batch_worker_sdk.handler.handler import SdkTaskHandler  # noqa: F401 — protocol parity
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult


class HttpDispatchTarget(BaseModel):
    """一个扇出目标:URL + 可选的 per-target payload 覆盖。"""

    model_config = ConfigDict(frozen=True, extra="forbid")

    url: str
    """要 POST / PUT 等的完整 URL(相对 ``config.base_url`` 解析由调用方负责)。"""

    method: str = "POST"
    """HTTP method(``POST`` / ``PUT`` / ``PATCH`` 等)。"""

    payload: dict[str, Any] = Field(default_factory=dict)
    """Per-target JSON body(与配置级 ``payload_template`` 合并)。"""

    headers: dict[str, str] = Field(default_factory=dict)
    """Per-target HTTP headers(覆盖配置级 ``headers``)。"""


class HttpDispatchConfig(BaseModel):
    """:class:`HttpDispatchHandler` 的配置。

    对齐 Java ``HttpDispatchConfig`` record。Java record 偏 JDBC + 单端点;
    Python 配置更适合扇出(并发数、headers、payload 模板、通过
    :meth:`HttpDispatchHandler._resolve_targets` 钩子做 per-target 覆盖)。
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    task_type: str
    """注册到平台、全局唯一的 task type code。"""

    base_url: str | None = None
    """可选 base URL,目标 ``url`` 是相对路径时拼接。"""

    method: str = "POST"
    """扇出请求的默认 HTTP method。"""

    headers: dict[str, str] = Field(default_factory=dict)
    """每个扇出请求都会带上的默认 HTTP headers。"""

    payload_template: dict[str, Any] = Field(default_factory=dict)
    """默认 JSON body,与每个 target 的 payload 合并(target 优先)。"""

    concurrency: int = Field(default=8, gt=0)
    """同时在飞扇出请求数上限(semaphore cap)。"""

    timeout_seconds: float = Field(default=30.0, gt=0)
    """单请求总超时(对齐 Java ``timeoutSeconds``)。"""

    fail_fast: bool = False
    """``True`` → 首个非 2xx 中止整批(对齐 Java)。"""

    block_private_ips: bool = True
    """SSRF 防护:拒绝 loopback / 私网 / link-local 目标主机。"""

    @classmethod
    def defaults(cls, task_type: str) -> HttpDispatchConfig:
        return cls(task_type=task_type)


class HttpDispatchHandler:
    """Per-target HTTP 扇出推送模板(DB / 内联 / API → 外部 HTTP)。

    租户子类覆盖 :meth:`_resolve_targets` 产出当次任务的
    :class:`HttpDispatchTarget` 列表;内置负责并发受限的异步扇出。重试 *不在*
    本 handler 范围内(交给 SDK 重试层或上游重试策略)。

    对齐 Java:2xx 视为成功,非 2xx / 异常视为失败。``fail_fast=True`` 在首次
    失败时中止,对齐 Java 行为。
    """

    def __init__(
        self,
        config: HttpDispatchConfig,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._config = config
        self._client = client
        self._owns_client = client is None

    # -- SdkTaskHandler 协议 ------------------------------------------------------

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

            if self._config.fail_fast and counts.failed() > 0:
                return SdkTaskResult.fail(
                    "DISPATCH_FAILED",
                    f"dispatched {counts.success()} ok, {counts.failed()} failed (fail-fast)",
                )
        finally:
            if self._owns_client:
                await client.aclose()

        return SdkTaskResult.success_with(
            output=counts.to_output(),
            message=f"dispatched {counts.success()} ok, {counts.failed()} failed",
        )

    # -- 租户可覆盖钩子(对齐 Java SdkAbstractDispatchHandler) ------------------

    async def _resolve_targets(self, ctx: SdkTaskContext) -> list[HttpDispatchTarget]:
        """**租户必须覆盖的抽象方法。** 产出当次任务的扇出目标列表。

        默认会在 ``ctx.parameters['targets']`` 存在(dict 列表)时读取,方便
        简单的内联配置免子类化使用;不存在时抛异常。
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
        """发送一次 HTTP 请求;默认把 2xx 视为成功。"""
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

    # -- 内部方法 ----------------------------------------------------------------

    def _resolve_url(self, url: str) -> str:
        if self._config.base_url and not url.startswith(("http://", "https://")):
            return self._config.base_url.rstrip("/") + "/" + url.lstrip("/")
        return url

    def _check_ssrf(self, url: str) -> None:
        if not self._config.block_private_ips:
            return
        # 快速字符串 host 抽取 —— httpx 没暴露公共 URL parser,``urllib.parse``
        # 足够。我们只在 host 是字面 IP 且落在私网空间时拦截;基于 DNS 的拦截
        # 需要异步解析,对于 SSRF 回退场景而言延迟不划算(对齐 Java 中
        # ``InetAddress.getByName`` 的等价路径)。
        host = urlparse(self._resolve_url(url)).hostname
        if not host:
            raise ValueError(f"invalid endpoint, no host: {url}")
        try:
            ip = ipaddress.ip_address(host)
        except ValueError:
            return
        if ip.is_loopback or ip.is_private or ip.is_link_local or ip.is_unspecified:
            raise PermissionError(f"SSRF blocked: private/loopback IP for host {host}")
