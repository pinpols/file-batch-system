"""orchestrator ``/internal/*`` 协议的异步 HTTP 客户端。

对应 Java ``com.example.batch.sdk.internal.PlatformHttpClient`` —— 端点集合 /
请求头 / 幂等语义完全一致;底层使用 ``httpx.AsyncClient``,因为 Python 端
SDK 仅支持 async(详见 ``sdk-python/README.md`` Roadmap)。

已实现的端点(对齐 openapi ``docs/api/orchestrator-internal.openapi.yaml``,
所有 ``stable`` 路径均已接入;``beta`` / ``internal-only`` 路径也一并接入,
因为它们就是纯 HTTP 包装):

- POST /internal/workers/register                         (register)
- POST /internal/workers/{code}/heartbeat                 (heartbeat)
- POST /internal/workers/{code}/deactivate                (deactivate)
- POST /internal/workers/{code}/status                    (update_status)
- POST /internal/workers/{code}/drain                     (drain)
- POST /internal/tasks/{id}/claim                         (claim)
- POST /internal/tasks/{id}/report                        (report)
- POST /internal/tasks/{id}/renew                         (renew)

刻意不接入的 internal-only 运维端点(由控制台 / 平台运维直接调用,SDK 不应触及):

- POST /internal/workers/{code}/force-offline
- POST /internal/workers/{code}/takeover
- POST /internal/workers/{code}/warmup
- GET  /internal/workers/{code}/claimed-tasks
- POST /internal/tasks/{id}/cancel
- POST /internal/tasks/leases/renew-batch  (TODO 性能优化,见 P1-6)

写操作(``claim`` / ``report``)接受 ``idempotency_key`` 参数并通过
``Idempotency-Key`` 请求头透传 —— 行为与 Java client 一致。
"""

from __future__ import annotations

from types import TracebackType
from typing import Any

import httpx

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.retry._retry import ClientErrorCounter, with_retry


class PlatformHttpClient:
    """对平台 ``/internal/*`` HTTP API 的薄异步封装。

    在 worker 进程生命周期内持有一个 ``httpx.AsyncClient``;关停时调用
    :meth:`close`(或使用 ``async with``)以释放连接池,否则 pytest 会因
    socket 泄漏报警。

    Args:
        config: 已校验的 SDK 配置(见 :class:`BatchPlatformClientConfig`)。
        client: 可选的预构造 ``httpx.AsyncClient``,主要供使用
            ``pytest_httpx`` / ``respx`` 的测试场景。生产代码请保持
            ``None``,本类会依据 ``config`` 的 timeout 自行构造。
    """

    def __init__(
        self,
        config: BatchPlatformClientConfig,
        *,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self.config = config
        self._counter = ClientErrorCounter(
            threshold=config.client_error_fail_fast_threshold,
        )
        if client is not None:
            self._client = client
            self._owns_client = False
        else:
            timeout = httpx.Timeout(config.http_timeout.total_seconds())
            self._client = httpx.AsyncClient(
                base_url=config.base_url,
                timeout=timeout,
                headers={
                    "Accept": "application/json",
                },
            )
            self._owns_client = True

    # ─── 生命周期 ────────────────────────────────────────────────────

    async def close(self) -> None:
        """关闭由本对象创建的 ``httpx.AsyncClient``;注入的 client 不动。"""
        if self._owns_client:
            await self._client.aclose()

    async def __aenter__(self) -> PlatformHttpClient:
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.close()

    @property
    def client_error_counter(self) -> ClientErrorCounter:
        """暴露累计 4xx 计数器,供诊断 / 测试使用。"""
        return self._counter

    # ─── workers/* ─────────────────────────────────────────────────────

    async def register(self, body: dict[str, Any]) -> dict[str, Any]:
        """POST /internal/workers/register —— body 形状对齐 WorkerHeartbeatDto。"""
        return await self._post_json("/internal/workers/register", body)

    async def heartbeat(self, worker_code: str, body: dict[str, Any]) -> dict[str, Any]:
        """POST /internal/workers/{code}/heartbeat —— 返回平台 directive。"""
        return await self._post_json(f"/internal/workers/{worker_code}/heartbeat", body)

    async def deactivate(self, worker_code: str, body: dict[str, Any]) -> None:
        """POST /internal/workers/{code}/deactivate —— 优雅下线。"""
        await self._post_json(f"/internal/workers/{worker_code}/deactivate", body)

    async def update_status(self, worker_code: str, body: dict[str, Any]) -> dict[str, Any]:
        """POST /internal/workers/{code}/status —— 运维侧更新 worker 状态。

        对齐 orchestrator ``WorkerController.updateStatus`` —— **POST + body**
        (WorkerHeartbeatDto schema),不是 GET。平台返回 ``WorkerRegistryEntity``
        快照。SDK 一般不直接调用本端点(运维路径),保留是为了上层运维工具复用。
        """
        return await self._post_json(f"/internal/workers/{worker_code}/status", body)

    async def drain(self, worker_code: str, body: dict[str, Any]) -> dict[str, Any]:
        """POST /internal/workers/{code}/drain —— 运维侧主动触发 drain。"""
        return await self._post_json(f"/internal/workers/{worker_code}/drain", body)

    # ─── tasks/* ───────────────────────────────────────────────────────

    async def claim(
        self,
        task_id: int,
        idempotency_key: str,
        body: dict[str, Any],
    ) -> dict[str, Any]:
        """POST /internal/tasks/{id}/claim —— 返回 EffectiveTaskConfig。

        409 以正常返回值形式暴露(包含响应体),调用方无需 ``except
        ConflictError``,即可按 wire-protocol §B 分支处理"幂等已 claim"
        情况。
        """
        decoded, _status = await self.claim_status(task_id, idempotency_key, body)
        return decoded

    async def claim_status(
        self,
        task_id: int,
        idempotency_key: str,
        body: dict[str, Any],
    ) -> tuple[dict[str, Any], int]:
        """POST /internal/tasks/{id}/claim,返回 ``(响应体, HTTP 状态码)``。

        与 :meth:`claim` 调同一端点,但额外暴露 HTTP 状态码,让调用方
        (dispatcher)区分 2xx 真正认领 vs 409 幂等"已被他人/自己 claim"。
        409 时调用方必须**直接返回**:既不执行 handler,也不 REPORT —— 见
        contract fixture 08(``sdkMustNot: call /report``)。
        """
        resp = await self._post_json_raw(
            f"/internal/tasks/{task_id}/claim",
            body,
            idempotency_key=idempotency_key,
        )
        return _decode_body(resp), resp.status_code

    async def report(
        self,
        task_id: int,
        idempotency_key: str,
        body: dict[str, Any],
    ) -> dict[str, Any]:
        """POST /internal/tasks/{id}/report —— body 对应 TaskExecutionReportDto。"""
        return await self._post_json(
            f"/internal/tasks/{task_id}/report",
            body,
            idempotency_key=idempotency_key,
        )

    async def renew(self, task_id: int, body: dict[str, Any]) -> dict[str, Any]:
        """POST /internal/tasks/{id}/renew —— body 对应 TaskClaimRequest 字段。"""
        return await self._post_json(f"/internal/tasks/{task_id}/renew", body)

    # ─── 内部实现 ────────────────────────────────────────────────────

    def _headers(self, idempotency_key: str | None) -> dict[str, str]:
        h: dict[str, str] = {
            "X-Batch-Tenant-Id": self.config.tenant_id,
        }
        if self.config.api_key:
            h["X-Batch-Api-Key"] = self.config.api_key
        if idempotency_key:
            h["Idempotency-Key"] = idempotency_key
        return h

    async def _post_json_raw(
        self,
        path: str,
        body: dict[str, Any] | None,
        *,
        idempotency_key: str | None = None,
    ) -> httpx.Response:
        headers = self._headers(idempotency_key)

        async def factory() -> httpx.Response:
            return await self._client.post(path, json=body or {}, headers=headers)

        return await with_retry(
            factory,
            max_attempts=self.config.retry_max_attempts,
            base_delay_s=self.config.retry_base_delay.total_seconds(),
            counter=self._counter,
        )

    async def _post_json(
        self,
        path: str,
        body: dict[str, Any] | None,
        *,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        resp = await self._post_json_raw(path, body, idempotency_key=idempotency_key)
        return _decode_body(resp)


def _decode_body(resp: httpx.Response) -> dict[str, Any]:
    """解析 JSON body;空 2xx body 容忍(如 deactivate 返回 200 空体)。"""
    if not resp.content:
        return {}
    try:
        decoded = resp.json()
    except ValueError:
        return {}
    if isinstance(decoded, dict):
        return decoded
    # 部分端点(如批量续约)返回数组,这里包成 dict 以让调用方的签名保持
    # 同质;当前的所有端点都返回 object,本分支属于防御性兜底。
    return {"_array": decoded}
