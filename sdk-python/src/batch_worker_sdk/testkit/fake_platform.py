"""进程内异步 fake 平台 —— 对标 Java ``FakeBatchPlatform``。

用 ``aiohttp`` 服务绑定 ``127.0.0.1:0``(随机空闲端口)桩掉
orchestrator 的全部 8 个 ``/internal/*`` 端点。租户测试构造真实的
:class:`BatchPlatformClientConfig` 指向 :attr:`base_url`,被测 SDK
代码就能像驱动生产环境一样驱动这个 fake。

fake 同时维护一个 Kafka 形状的队列(``asyncio.Queue[dict]``),
已支持 Kafka 消费(Lane S 落地),同一夹具可端到端驱动 dispatch
而不用改测试代码。:meth:`dispatch_task` 当前是仅入队的副作用,
handler 测试显式从中拉取。
"""

from __future__ import annotations

import asyncio
import contextlib
import json
from types import TracebackType
from typing import Any

from aiohttp import web


class FakeBatchPlatform:
    """orchestrator + dispatch channel 的进程内 fake。

    用法::

        async with FakeBatchPlatform() as fp:
            await fp.start()
            cfg = BatchPlatformClientConfig(base_url=fp.base_url, ...)
            # ... 用 fp 驱动被测 SDK ...
            assert fp.get_reports()[0]["taskId"] == 42

    行为概述:

    - 所有 POST ``/internal/*`` 端点都回 200 + 桩 JSON。
      ``register`` / ``claim`` / ``report`` 的请求体记录到
      ``self._registrations`` / ``self._claims`` / ``self._reports``
      (dict 列表)。
    - ``heartbeat`` 返回 :meth:`set_heartbeat_directive` 配置的内容
      (默认 ``{}``)。
    - ``renew`` 对通过 :meth:`set_cancel_for_task` 注册过的 taskId
      返回 ``{"cancelRequested": True}``,其余返回 ``{}``。

    暂未实现(留给 Lane S/T 后续):

    - 真实 Kafka —— :meth:`dispatch_task` 仅入队。
    - 5xx / 4xx 错误注入 —— 第一个合约 fixture 需要时再加。
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

    # ─── 生命周期 ──────────────────────────────────────────────────────

    async def start(self) -> str:
        """绑定 HTTP 服务,返回分配到的 ``base_url``。"""
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
        # aiohttp 不直接暴露端口,要扒底层 socket。
        # ``_server`` 是私有属性,但在 aiohttp 3.x 中稳定。
        server = self._site._server
        assert server is not None, "site has no underlying server"
        sockets = getattr(server, "sockets", None)
        assert sockets, "site has no bound socket"
        port = sockets[0].getsockname()[1]
        self._base_url = f"http://127.0.0.1:{port}"
        return self._base_url

    async def stop(self) -> None:
        """关闭 HTTP 服务,幂等。"""
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

    # ─── 对外测试驱动接口 ──────────────────────────────────────────────

    @property
    def base_url(self) -> str:
        """绑定的 ``http://127.0.0.1:<port>`` —— 仅 :meth:`start` 之后可用。"""
        if self._base_url is None:
            raise RuntimeError("FakeBatchPlatform not started")
        return self._base_url

    def dispatch_task(self, task: dict[str, Any]) -> None:
        """把一条任务派发消息推入内存队列。

        Lane S 接上真实 Kafka 消费者之前,测试通过
        :meth:`pending_dispatches` 断言。``task`` 是自由形状的 dict,
        匹配 wire-protocol 的 ``TaskDispatchMessage``。
        """
        # ``put_nowait`` 是同步安全的 —— Queue 默认无界。
        self._dispatch_queue.put_nowait(task)

    async def take_dispatch(self, timeout_s: float = 1.0) -> dict[str, Any]:
        """弹出下一条派发消息;无消息时抛 ``TimeoutError``。

        ``timeout_s`` 带单位后缀以规避 ASYNC109
        (``timeout`` 在 async lint 中是哨兵名)。
        """
        async with asyncio.timeout(timeout_s):
            return await self._dispatch_queue.get()

    def pending_dispatches(self) -> int:
        """队列中尚未被消费的派发消息数。"""
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
        """控制下一次 heartbeat POST fake 返回的内容。

        按调用清零会让测试意外失败;采用 latest-wins 策略 —— 一次
        ``set_heartbeat_directive`` 在覆盖前会跨多次 heartbeat 持续生效。
        """
        self._heartbeat_directive = dict(directive)

    def set_cancel_for_task(self, task_id: int) -> None:
        """让 ``/internal/tasks/{task_id}/renew`` 回 ``cancelRequested=True``。"""
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
    """尽力 JSON 解码:空 body → ``{}``;格式错误 → ``{}``。"""
    raw = await request.read()
    if not raw:
        return {}
    with contextlib.suppress(json.JSONDecodeError, UnicodeDecodeError):
        decoded = json.loads(raw.decode("utf-8"))
        if isinstance(decoded, dict):
            return decoded
    return {}
