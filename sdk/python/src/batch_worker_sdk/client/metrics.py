"""运行时指标快照 —— 对齐 Java ``io.github.pinpols.batch.sdk.client.SdkClientMetrics``。

供租户把 SDK 运行态接到自家 Prometheus / OpenTelemetry:``client.metrics()`` 返回
一个不可变快照,字段与 Java record 一一对应(语义相同,命名按 PEP 8 snake_case)。
线程/协程安全:全部读已有的原子/单协程状态,不做 IO。
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class SdkClientMetrics:
    """SDK 运行时指标的不可变快照(对齐 Java ``SdkClientMetrics`` record 的 11 字段)。"""

    tenant_id: str
    worker_code: str
    started: bool
    """已 start 且未 stop。"""
    healthy: bool
    """仍在正常接派单 = ``started and not dispatcher_fatal and not consumer_crashed``。
    等价 Java ``isHealthy()`` / ``SdkClientMetrics.healthy``,可直接喂 liveness/readiness。"""
    in_flight_task_count: int
    """当前在执行的 handler 数(dispatcher in-flight)。"""
    max_concurrent_tasks: int
    """配置的并发上限。"""
    registered_handler_count: int
    """已注册的 handler(taskType)数。"""
    dispatcher_fatal: bool
    """CLAIM 遇 401/403 → dispatcher 中毒,后续消息静默 drop(等运维介入)。"""
    dispatcher_draining: bool
    """优雅停机中(仍在续约 lease,不接新任务)。"""
    consumer_crashed: bool
    """Kafka poll 循环因异常(非取消/非优雅停)死亡 —— 消费已停但进程还在。"""
    kafka_consumer_lag: int
    """最大分区消费滞后;``-1`` = 未接线(Python aiokafka 暂不采集,占位对齐 Java 的 null→-1)。"""
