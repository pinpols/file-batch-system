"""Async in-process platform fake — mirrors Java ``FakeBatchPlatform``.

Stubs all 8 orchestrator ``/internal/*`` endpoints with an
``aiohttp`` server bound to ``127.0.0.1:0`` (random free port).
Tenant tests configure a real :class:`BatchPlatformClientConfig`
pointing at :attr:`base_url`; the SDK code under test then drives the
fake the same way it would drive production.

The fake also keeps a Kafka-shaped queue (``asyncio.Queue[dict]``) so
that once Lane S (Kafka consumer) lands, the same harness can drive
end-to-end dispatch without changing test code. Until then,
:meth:`dispatch_task` is a queue-only side effect (handler tests pull
from it explicitly).
"""

from __future__ import annotations

import asyncio
import contextlib
import json
from types import TracebackType
from typing import Any

from aiohttp import web


class FakeBatchPlatform:
    """In-process fake of the orchestrator + dispatch channel.

    Usage::

        async with FakeBatchPlatform() as fp:
            await fp.start()
            cfg = BatchPlatformClientConfig(base_url=fp.base_url, ...)
            # ... drive SDK against fp ...
            assert fp.get_reports()[0]["taskId"] == 42

    Behaviour summary:

    - All POST ``/internal/*`` endpoints respond 200 with a stubbed
      JSON body. ``register`` / ``claim`` / ``report`` payloads are
      recorded into ``self._registrations`` / ``self._claims`` /
      ``self._reports`` (lists of dict).
    - ``heartbeat`` responds with whatever :meth:`set_heartbeat_directive`
      configured (defaults to ``{}``).
    - ``renew`` responds with ``{"cancelRequested": True}`` for taskIds
      passed to :meth:`set_cancel_for_task`, ``{}`` otherwise.

    Not implemented (kept for Lane S/T follow-up):

    - Real Kafka — :meth:`dispatch_task` only pushes onto the queue.
    - 5xx / 4xx error injection — add when first contract fixture needs it.
    """

    def __init__(self) -> None:
        self._app: web.Application | None = None
        self._runner: web.AppRunner | None = None
        self._site: web.TCPSite | None = None
        self._base_url: str | None = None

        self._registrations: list[dict[str, Any]] = []
        self._heartbeats: list[dict[str, Any]] = []
        self._claims: list[dict[str, Any]] = []
        self._reports: list[dict[str, Any]] = []
        self._renews: list[dict[str, Any]] = []
        self._deactivates: list[dict[str, Any]] = []
        self._drains: list[dict[str, Any]] = []

        self._heartbeat_directive: dict[str, Any] = {}
        self._cancel_task_ids: set[int] = set()
        self._dispatch_queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()

    # ─── lifecycle ─────────────────────────────────────────────────────

    async def start(self) -> str:
        """Bind the HTTP server. Returns the assigned ``base_url``."""
        if self._site is not None:
            raise RuntimeError("FakeBatchPlatform already started")
        app = web.Application()
        app.router.add_post("/internal/workers/register", self._handle_register)
        app.router.add_post("/internal/workers/{code}/heartbeat", self._handle_heartbeat)
        app.router.add_post("/internal/workers/{code}/deactivate", self._handle_deactivate)
        app.router.add_get("/internal/workers/{code}/status", self._handle_status)
        app.router.add_post("/internal/workers/{code}/drain", self._handle_drain)
        app.router.add_post("/internal/tasks/{id}/claim", self._handle_claim)
        app.router.add_post("/internal/tasks/{id}/report", self._handle_report)
        app.router.add_post("/internal/tasks/{id}/renew", self._handle_renew)
        self._app = app
        self._runner = web.AppRunner(app)
        await self._runner.setup()
        self._site = web.TCPSite(self._runner, "127.0.0.1", 0)
        await self._site.start()
        # aiohttp doesn't expose port directly; dig into the underlying socket.
        # ``_server`` is private but stable across aiohttp 3.x.
        server = self._site._server
        assert server is not None, "site has no underlying server"
        sockets = getattr(server, "sockets", None)
        assert sockets, "site has no bound socket"
        port = sockets[0].getsockname()[1]
        self._base_url = f"http://127.0.0.1:{port}"
        return self._base_url

    async def stop(self) -> None:
        """Shut down the HTTP server. Idempotent."""
        if self._runner is not None:
            await self._runner.cleanup()
            self._runner = None
        self._site = None
        self._app = None
        self._base_url = None

    async def __aenter__(self) -> FakeBatchPlatform:
        await self.start()
        return self

    async def __aexit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        await self.stop()

    # ─── public test-driver surface ────────────────────────────────────

    @property
    def base_url(self) -> str:
        """Bound ``http://127.0.0.1:<port>`` — only valid after :meth:`start`."""
        if self._base_url is None:
            raise RuntimeError("FakeBatchPlatform not started")
        return self._base_url

    def dispatch_task(self, task: dict[str, Any]) -> None:
        """Push a task dispatch message onto the in-memory queue.

        Tests assert on :meth:`pending_dispatches` until Lane S wires a
        real Kafka consumer. ``task`` is a free-form dict matching the
        wire-protocol ``TaskDispatchMessage`` shape.
        """
        # ``put_nowait`` is sync-safe — Queue is unbounded by default.
        self._dispatch_queue.put_nowait(task)

    async def take_dispatch(self, timeout_s: float = 1.0) -> dict[str, Any]:
        """Pop the next dispatch message; raises ``TimeoutError`` if none.

        ``timeout_s`` parameter named with the unit suffix to dodge ASYNC109
        (``timeout`` is a sentinel name in async lints).
        """
        async with asyncio.timeout(timeout_s):
            return await self._dispatch_queue.get()

    def pending_dispatches(self) -> int:
        """Number of un-consumed dispatch messages still queued."""
        return self._dispatch_queue.qsize()

    def get_registrations(self) -> list[dict[str, Any]]:
        return list(self._registrations)

    def get_reports(self) -> list[dict[str, Any]]:
        return list(self._reports)

    def get_heartbeats(self) -> list[dict[str, Any]]:
        return list(self._heartbeats)

    def get_claims(self) -> list[dict[str, Any]]:
        return list(self._claims)

    def get_renews(self) -> list[dict[str, Any]]:
        return list(self._renews)

    def get_deactivates(self) -> list[dict[str, Any]]:
        return list(self._deactivates)

    def get_drains(self) -> list[dict[str, Any]]:
        return list(self._drains)

    def set_heartbeat_directive(self, directive: dict[str, Any]) -> None:
        """Control what the fake returns on the next heartbeat POST.

        Cleared per-call would surprise tests; we keep latest-wins so a
        single ``set_heartbeat_directive`` survives across multiple
        heartbeats until overridden.
        """
        self._heartbeat_directive = dict(directive)

    def set_cancel_for_task(self, task_id: int) -> None:
        """Make ``/internal/tasks/{task_id}/renew`` respond ``cancelRequested=True``."""
        self._cancel_task_ids.add(int(task_id))

    def clear_cancel_for_task(self, task_id: int) -> None:
        self._cancel_task_ids.discard(int(task_id))

    # ─── HTTP handlers ─────────────────────────────────────────────────

    async def _handle_register(self, request: web.Request) -> web.Response:
        body = await _read_json(request)
        self._registrations.append(body)
        return web.json_response({"workerCode": body.get("workerCode"), "registered": True})

    async def _handle_heartbeat(self, request: web.Request) -> web.Response:
        body = await _read_json(request)
        body.setdefault("_workerCode", request.match_info["code"])
        self._heartbeats.append(body)
        return web.json_response(self._heartbeat_directive)

    async def _handle_deactivate(self, request: web.Request) -> web.Response:
        body = await _read_json(request)
        body.setdefault("_workerCode", request.match_info["code"])
        self._deactivates.append(body)
        return web.json_response({})

    async def _handle_status(self, request: web.Request) -> web.Response:
        return web.json_response({"workerCode": request.match_info["code"], "state": "NORMAL"})

    async def _handle_drain(self, request: web.Request) -> web.Response:
        body = await _read_json(request)
        body.setdefault("_workerCode", request.match_info["code"])
        self._drains.append(body)
        return web.json_response({"draining": True})

    async def _handle_claim(self, request: web.Request) -> web.Response:
        body = await _read_json(request)
        task_id = int(request.match_info["id"])
        body.setdefault("_taskId", task_id)
        self._claims.append(body)
        return web.json_response({"taskId": task_id, "claimed": True})

    async def _handle_report(self, request: web.Request) -> web.Response:
        body = await _read_json(request)
        task_id = int(request.match_info["id"])
        body.setdefault("taskId", task_id)
        self._reports.append(body)
        return web.json_response({})

    async def _handle_renew(self, request: web.Request) -> web.Response:
        body = await _read_json(request)
        task_id = int(request.match_info["id"])
        body.setdefault("_taskId", task_id)
        self._renews.append(body)
        cancel = task_id in self._cancel_task_ids
        return web.json_response({"cancelRequested": cancel})


async def _read_json(request: web.Request) -> dict[str, Any]:
    """Best-effort JSON decode. Empty body → ``{}``; malformed → ``{}``."""
    raw = await request.read()
    if not raw:
        return {}
    with contextlib.suppress(json.JSONDecodeError, UnicodeDecodeError):
        decoded = json.loads(raw.decode("utf-8"))
        if isinstance(decoded, dict):
            return decoded
    return {}
