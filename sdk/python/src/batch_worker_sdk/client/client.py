"""SDK 高阶入口,对齐 Java ``BatchPlatformClient``。

唯一面向用户的客户端类,负责把各部件粘合到一起:

1. :class:`PlatformHttpClient` —— HTTP 协议层。
2. :class:`TaskDispatcher` —— Kafka 消息 → CLAIM → 执行 → REPORT 流水线。
3. :class:`KafkaTaskConsumer` —— 派发消息的 Kafka 消费者。
4. :class:`HeartbeatScheduler` + :class:`LeaseRenewalScheduler` —— 心跳 +
   租约续约两个常驻调度任务。

生命周期:

- :meth:`register_handler` 在 start 之前注册 handler(对齐 Java
  ``Builder.register``)。
- :meth:`start` 顺序为 ``validate_timings`` → 向 orch 注册 → 启动调度器 →
  启动 Kafka 消费者。**调度器必须先于 Kafka 启动**:若第一条任务在第一次
  心跳之前到达,orch 可能还不知道我们存在(Java 端 ``BatchPlatformClient.start``
  采用同样顺序)。
- :meth:`stop` 走 :func:`_lifecycle.stop_with_timeout` 的分阶段预算化停机
  流程。

测试侧通过 ``dispatcher`` / ``kafka_consumer`` 注入假实现,避免对真实
Kafka / dispatcher 产生耦合。
"""

from __future__ import annotations

import asyncio
import logging
from datetime import timedelta
from typing import Any, Protocol

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.idempotent import SdkIdempotencyStore, wrap_idempotent
from batch_worker_sdk.internal import _fingerprint, _lifecycle
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.scheduler._heartbeat import DispatcherLike, HeartbeatScheduler
from batch_worker_sdk.scheduler._lease import LeaseRenewalScheduler

# 与 Java BatchPlatformClient.start() 一致的固定 worker_group 标识 —— 自托管 SDK
# worker 与平台原生 worker(import / export / process / dispatch / atomic)在
# worker_registry 表里通过此列区分。
_WORKER_GROUP = "sdk-self-hosted"

logger = logging.getLogger(__name__)


class _KafkaConsumerLike(Protocol):
    """``KafkaTaskConsumer`` 中 client 实际触及的子集。"""

    async def start(self) -> None: ...
    async def stop(self) -> None: ...


# 工厂别名 —— 集中放在这里,方便统一调整类型
DispatcherFactory = Any  # callable: (config, http, handlers) -> DispatcherLike
KafkaFactory = Any  # callable: (config, dispatcher) -> _KafkaConsumerLike


class BatchPlatformClient:
    """SDK 入口,对齐 Java ``com.example.batch.sdk.client.BatchPlatformClient``。

    典型用法::

        client = BatchPlatformClient(config)
        client.register_handler(MyImportHandler())
        await client.start()
        try:
            await stop_signal.wait()  # SIGTERM hook
        finally:
            await client.stop(timeout=30)

    Args:
        config: 已通过校验的 :class:`BatchPlatformClientConfig`。:meth:`start`
            会再跑一遍 timing 校验,以便绕过 pydantic 构造的配置也能 fail-fast。
        http: 测试场景下可注入预构造的 :class:`PlatformHttpClient`;生产代码
            保持 ``None`` 由本类自行创建。
        dispatcher_factory / kafka_factory: 用于注入 dispatcher /
            ``KafkaTaskConsumer`` 的工厂。为 ``None`` 时 :meth:`start` 内部
            会按需 lazy import 默认实现,避免在 import 阶段强制依赖
            ``aiokafka`` 等可选依赖。
    """

    def __init__(
        self,
        config: BatchPlatformClientConfig,
        *,
        http: PlatformHttpClient | None = None,
        dispatcher_factory: DispatcherFactory | None = None,
        kafka_factory: KafkaFactory | None = None,
        idempotency_store: SdkIdempotencyStore | None = None,
    ) -> None:
        self._config = config
        self._http: PlatformHttpClient = http if http is not None else PlatformHttpClient(config)
        self._owns_http: bool = http is None
        self._handlers: dict[str, SdkTaskHandler] = {}
        self._dispatcher_factory = dispatcher_factory
        self._kafka_factory = kafka_factory
        self._idempotency_store = idempotency_store

        self._dispatcher: DispatcherLike | None = None
        self._kafka: _KafkaConsumerLike | None = None
        self._heartbeat: HeartbeatScheduler | None = None
        self._lease: LeaseRenewalScheduler | None = None

        self._started: bool = False
        self._start_lock: asyncio.Lock = asyncio.Lock()

    # ─── start 之前的配置 ──────────────────────────────────────────────

    def register_handler(self, handler: SdkTaskHandler) -> None:
        """注册一个 :class:`SdkTaskHandler`(对齐 Java ``Builder.register``)。

        必须在 :meth:`start` 之前调用。``task_type`` 重复时直接抛错,让配置
        失误的 worker 早爆错,而不是悄悄丢弃后注册的 handler。
        """
        if self._started:
            raise RuntimeError("cannot register handlers after start()")
        task_type = handler.task_type()
        if not task_type or not task_type.strip():
            raise ValueError("handler.task_type() must be non-blank")
        if task_type in self._handlers:
            raise ValueError(f"duplicate handler for task_type={task_type!r}")
        self._handlers[task_type] = handler

    # ─── 可观察状态 ───────────────────────────────────────────────────

    @property
    def started(self) -> bool:
        return self._started

    @property
    def dispatcher(self) -> DispatcherLike | None:
        """已启动后的底层 dispatcher,暴露给测试和运维。"""
        return self._dispatcher

    @property
    def http(self) -> PlatformHttpClient:
        """底层 HTTP client,暴露给测试和高级用户。"""
        return self._http

    # ─── 生命周期 ────────────────────────────────────────────────────

    async def start(self) -> None:
        """完整的启动序列。

        顺序:

        1. ``config._validate_timings()`` —— 再跑一次以防调用方绕过 pydantic
           构造 config(带保险作用)。
        2. ``http.register`` —— orch / api-key / 网络异常立即 fail-fast。
        3. 构建 dispatcher 与调度器。
        4. 启动心跳 + 租约续约,确保第一条 Kafka 消息到达前 orch 已知晓本
           worker 存活。
        5. 启动 Kafka 消费者。

        Raises:
            RuntimeError: 重复 start 或未注册任何 handler。
            ValueError: timing 校验失败。
            PlatformError: register 调用失败。
        """
        async with self._start_lock:
            if self._started:
                raise RuntimeError("BatchPlatformClient already started")
            if not self._handlers:
                raise RuntimeError("at least one SdkTaskHandler must be registered")

            self._config._validate_timings()

            await self._http.register(self._build_register_body())

            self._dispatcher = self._build_dispatcher()
            self._heartbeat = HeartbeatScheduler(
                self._config,
                self._http,
                self._dispatcher,
                worker_group=_WORKER_GROUP,
                capability_tags=sorted(self._handlers.keys()),
            )
            self._lease = LeaseRenewalScheduler(self._config, self._http, self._dispatcher)

            # 调度器先,Kafka 后 —— 详见本类的 docstring。
            await self._heartbeat.start()
            await self._lease.start()

            self._kafka = self._build_kafka(self._dispatcher)
            if self._kafka is not None:
                await self._kafka.start()

            self._started = True
            logger.info(
                "BatchPlatformClient started: tenant=%s worker=%s handlers=%s",
                self._config.tenant_id,
                self._config.worker_code,
                sorted(self._handlers.keys()),
            )

    async def stop(self, timeout: timedelta | float = 30.0) -> None:  # noqa: ASYNC109 — total-budget semantic, not per-await
        """优雅停机。

        实际停机逻辑由 :func:`_lifecycle.stop_with_timeout` 提供:把 timeout
        预算切成 Kafka stop / in-flight drain / scheduler stop / deactivate
        几个阶段,任何阶段超时只 WARN 不抛,保证整体 wall-clock 不会超过预算
        过多。
        """
        if not self._started:
            return
        timeout_s = timeout.total_seconds() if isinstance(timeout, timedelta) else float(timeout)
        await _lifecycle.stop_with_timeout(self, timeout_s)
        if self._owns_http:
            await self._http.close()
        self._started = False

    # ─── 内部辅助 ────────────────────────────────────────────────────

    def _build_register_body(self) -> dict[str, Any]:
        """构造 worker-register 请求体,字段形状对齐 Java ``WorkerHeartbeatDto``。"""
        from batch_worker_sdk.constants import SCHEMA_VERSIONS_SUPPORTED  # noqa: PLC0415
        from batch_worker_sdk.scheduler._heartbeat import _utc_now_iso  # noqa: PLC0415

        body: dict[str, Any] = {
            "tenantId": self._config.tenant_id,
            "workerCode": self._config.worker_code,
            "workerGroup": _WORKER_GROUP,
            "status": "RUNNING",
            "heartbeatAt": _utc_now_iso(),
            "currentLoad": 0,
            "capabilityTags": sorted(self._handlers.keys()),
            "sdkVersion": self._config.sdk_version,
            # SDK-#536 register-time protocol-version gate: advertise the SDK's
            # current major (last of SCHEMA_VERSIONS_SUPPORTED) so the platform
            # identifies + accepts us. Register only — heartbeat carries null.
            "protocolVersion": SCHEMA_VERSIONS_SUPPORTED[-1],
        }
        # SDK-P5-3 运行指纹:host/pid 尽力采集,None 字段不写入。
        host_name = _fingerprint.host_name()
        if host_name is not None:
            body["hostName"] = host_name
        host_ip = _fingerprint.host_ip()
        if host_ip is not None:
            body["hostIp"] = host_ip
        body["processId"] = _fingerprint.process_id()
        if self._config.build_id:
            body["buildId"] = self._config.build_id
        descriptors: list[dict[str, Any]] = []
        for handler in self._handlers.values():
            desc = handler.descriptor()
            if desc is not None:
                # Pydantic 模型 → dict;pydantic v2 优先用 model_dump
                if hasattr(desc, "model_dump"):
                    descriptors.append(desc.model_dump(exclude_none=True))
                else:
                    descriptors.append(dict(desc))
        if descriptors:
            body["taskTypes"] = descriptors
        return body

    def _build_dispatcher(self) -> DispatcherLike:
        """构造 dispatcher;测试可通过 ``dispatcher_factory`` 注入,避免依赖默认实现。"""
        if self._dispatcher_factory is not None:
            built: DispatcherLike = self._dispatcher_factory(
                self._config, self._http, self._decorated_handlers()
            )
            return built
        # 默认走 ``batch_worker_sdk.dispatcher.dispatcher.TaskDispatcher``。
        from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher  # noqa: PLC0415

        return TaskDispatcher(
            self._config,
            self._http,
            dict(self._handlers),
            idempotency_store=self._idempotency_store,
        )

    def _decorated_handlers(self) -> dict[str, SdkTaskHandler]:
        """返回 dispatcher 可直接使用的 handler 表,自动织入声明式幂等。"""
        return {
            task_type: wrap_idempotent(handler, self._idempotency_store)
            for task_type, handler in self._handlers.items()
        }

    def _build_kafka(self, dispatcher: DispatcherLike) -> _KafkaConsumerLike | None:
        """构造 Kafka 消费者,仅在显式提供 ``kafka_factory`` 时启用。

        此处刻意 **不** 自动 import ``batch_worker_sdk.internal._kafka`` —— Kafka
        config 字段是可选的,只有需要消费 Kafka 派发的部署才必填。未注入工
        厂时仅运行 HTTP heartbeat + 租约续约的 scheduler-only 模式。
        """
        if self._kafka_factory is None:
            # 配了 kafka_bootstrap → 自动建内置 aiokafka 消费器(对齐 Go 样例「有 broker
            # 即消费」),无需调用方显式注入工厂。否则回退 scheduler-only。
            if getattr(self._config, "kafka_bootstrap", None):
                # 延迟 import(本文件刻意不在顶层 import _kafka):只有真要建消费器时才拉入
                # kafka 机制,保持 scheduler-only 部署的最小依赖面。
                from batch_worker_sdk.internal._kafka import (  # noqa: PLC0415
                    KafkaTaskConsumer,
                )

                logger.info(
                    "auto-building KafkaTaskConsumer from config "
                    "(kafka_bootstrap=%s, pattern=%s)",
                    self._config.kafka_bootstrap,
                    self._config.kafka_topic_pattern,
                )
                auto: _KafkaConsumerLike = KafkaTaskConsumer(self._config, dispatcher)  # type: ignore[arg-type]
                return auto
            logger.info(
                "no kafka_factory and no kafka_bootstrap; running scheduler-only "
                "(HTTP heartbeat + lease renew still active)"
            )
            return None
        built: _KafkaConsumerLike = self._kafka_factory(self._config, dispatcher)
        return built
