"""Async HTTP client for the orchestrator ``/internal/*`` protocol.

Python equivalent of
``com.example.batch.sdk.internal.PlatformHttpClient`` — same endpoint
set, same headers, same idempotency semantics, but built on
``httpx.AsyncClient`` because Python BYO SDK is async-only (see
``sdk-python/README.md`` Roadmap).

Endpoints implemented in P1 (mirrors openapi
``docs/api/orchestrator-internal.openapi.yaml`` — every ``stable``
path is wired up; ``beta`` / ``internal-only`` paths are wired too
for completeness since they are pure HTTP wrappers):

- POST /internal/workers/register                         (register)
- POST /internal/workers/{code}/heartbeat                 (heartbeat)
- POST /internal/workers/{code}/deactivate                (deactivate)
- GET  /internal/workers/{code}/status                    (get_status)
- POST /internal/workers/{code}/drain                     (drain)
- POST /internal/tasks/{id}/claim                         (claim)
- POST /internal/tasks/{id}/report                        (report)
- POST /internal/tasks/{id}/renew                         (renew)

Write operations (``claim`` / ``report``) accept an
``idempotency_key`` argument and propagate it via the
``Idempotency-Key`` header — same as the Java client.
"""

from __future__ import annotations

from types import TracebackType
from typing import Any

import httpx

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.retry._retry import ClientErrorCounter, with_retry


class PlatformHttpClient:
    """Thin async wrapper over the platform ``/internal/*`` HTTP API.

    Owns one ``httpx.AsyncClient`` for the lifetime of the worker
    process. Call :meth:`close` (or use ``async with``) on shutdown to
    drain the connection pool — otherwise pytest will warn about
    leaked sockets.

    Args:
        config: Validated SDK config (see :class:`BatchPlatformClientConfig`).
        client: Optional preconfigured ``httpx.AsyncClient`` — primarily
            for tests with ``pytest_httpx`` or ``respx``. Production
            callers should leave this ``None`` so we build a client
            with the timeout from ``config``.
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

    # ─── lifecycle ─────────────────────────────────────────────────────

    async def close(self) -> None:
        """Close the underlying ``httpx.AsyncClient`` if we own it."""
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
        """Expose the cumulative 4xx counter for diagnostics / tests."""
        return self._counter

    # ─── workers/* ─────────────────────────────────────────────────────

    async def register(self, body: dict[str, Any]) -> dict[str, Any]:
        """POST /internal/workers/register — body schema = WorkerHeartbeatDto."""
        return await self._post_json("/internal/workers/register", body)

    async def heartbeat(self, worker_code: str, body: dict[str, Any]) -> dict[str, Any]:
        """POST /internal/workers/{code}/heartbeat — returns platform directive."""
        return await self._post_json(f"/internal/workers/{worker_code}/heartbeat", body)

    async def deactivate(self, worker_code: str, body: dict[str, Any]) -> None:
        """POST /internal/workers/{code}/deactivate — graceful offline."""
        await self._post_json(f"/internal/workers/{worker_code}/deactivate", body)

    async def get_status(self, worker_code: str) -> dict[str, Any]:
        """GET /internal/workers/{code}/status — single-worker dashboard data."""
        return await self._get_json(f"/internal/workers/{worker_code}/status")

    async def drain(self, worker_code: str, body: dict[str, Any]) -> dict[str, Any]:
        """POST /internal/workers/{code}/drain — ops-initiated drain."""
        return await self._post_json(f"/internal/workers/{worker_code}/drain", body)

    # ─── tasks/* ───────────────────────────────────────────────────────

    async def claim(
        self,
        task_id: int,
        idempotency_key: str,
        body: dict[str, Any],
    ) -> dict[str, Any]:
        """POST /internal/tasks/{id}/claim — returns EffectiveTaskConfig.

        409 surfaces as a normal return (response body included) so
        callers can detect the idempotent-already-claimed path per
        wire-protocol §B without an ``except ConflictError``.
        """
        return await self._post_json(
            f"/internal/tasks/{task_id}/claim",
            body,
            idempotency_key=idempotency_key,
        )

    async def report(
        self,
        task_id: int,
        idempotency_key: str,
        body: dict[str, Any],
    ) -> dict[str, Any]:
        """POST /internal/tasks/{id}/report — body = TaskExecutionReportDto."""
        return await self._post_json(
            f"/internal/tasks/{task_id}/report",
            body,
            idempotency_key=idempotency_key,
        )

    async def renew(self, task_id: int, body: dict[str, Any]) -> dict[str, Any]:
        """POST /internal/tasks/{id}/renew — body = TaskClaimRequest fields."""
        return await self._post_json(f"/internal/tasks/{task_id}/renew", body)

    # ─── internals ─────────────────────────────────────────────────────

    def _headers(self, idempotency_key: str | None) -> dict[str, str]:
        h: dict[str, str] = {
            "X-Batch-Tenant-Id": self.config.tenant_id,
        }
        if self.config.api_key:
            h["X-Batch-Api-Key"] = self.config.api_key
        if idempotency_key:
            h["Idempotency-Key"] = idempotency_key
        return h

    async def _post_json(
        self,
        path: str,
        body: dict[str, Any] | None,
        *,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        headers = self._headers(idempotency_key)

        async def factory() -> httpx.Response:
            return await self._client.post(path, json=body or {}, headers=headers)

        resp = await with_retry(
            factory,
            max_attempts=self.config.retry_max_attempts,
            base_delay_s=self.config.retry_base_delay.total_seconds(),
            counter=self._counter,
        )
        return _decode_body(resp)

    async def _get_json(self, path: str) -> dict[str, Any]:
        headers = self._headers(None)

        async def factory() -> httpx.Response:
            return await self._client.get(path, headers=headers)

        resp = await with_retry(
            factory,
            max_attempts=self.config.retry_max_attempts,
            base_delay_s=self.config.retry_base_delay.total_seconds(),
            counter=self._counter,
        )
        return _decode_body(resp)


def _decode_body(resp: httpx.Response) -> dict[str, Any]:
    """Parse JSON body; tolerate empty 2xx bodies (deactivate returns 200 no-body)."""
    if not resp.content:
        return {}
    try:
        decoded = resp.json()
    except ValueError:
        return {}
    if isinstance(decoded, dict):
        return decoded
    # Some endpoints (lease/renew-batch in P2) return arrays; wrap so
    # caller signature stays homogeneous. P1 endpoints all return
    # objects, so this branch is defensive only.
    return {"_array": decoded}
