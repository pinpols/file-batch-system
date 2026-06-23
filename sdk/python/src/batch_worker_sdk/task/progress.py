"""单任务进度 / checkpoint 槽位(P4 实现)。

对齐 Java ``io.github.pinpols.batch.sdk.task.ProgressReporter`` —— "最新值胜出"
语义:handler 在紧循环里更新进度会覆盖前一份快照;租约续期 tick 采样
:meth:`latest`,作为 renew 请求体的 ``details`` 字段上送。

并发模型
--------
Handler 协程通过 :meth:`report` 写入;租约续期任务
(由 ``LeaseRenewalScheduler`` 驱动的独立 ``asyncio.Task``)通过
:meth:`latest` 读取。即使常规情况下两者跑在**同一**事件循环里,Python SDK
公开 API 边界承诺 async-only,而 Java 等价物用 ``volatile``;这里用
:class:`threading.Lock`(开销低,约 ~50ns)保证快照指针原子性,
未来即便有 executor 把心跳卸到线程也无碍。

防御性拷贝
----------
:meth:`report` 对入参 dict 做**浅拷贝**,调用方可随意改原对象不影响续期任务。
:meth:`latest` 返回**浅拷贝**,消费方不能改存储里的快照。嵌套可变值
(list、dict)不深拷贝:这与 Java ``Map.copyOf`` 一致(后者也不深拷贝)。
需要改嵌套结构的调用方应每次 ``report`` 都构造新 dict(推荐写法)。

敏感数据守护
------------
``details`` 会持久化到 ``job_task`` 并对运维可见(见 Java
``ProgressReporter`` Javadoc)。存储前根据 :mod:`batch_worker_sdk._sensitive_keys`
筛 key,命中即 ``raise ValueError`` —— 与 Java Lane C
``SensitiveDataValidator`` 用意一致。
"""

from __future__ import annotations

import threading
from typing import Any

from batch_worker_sdk.internal._sensitive_keys import find_sensitive_keys


class ProgressReporter:
    """异步安全的单槽位进度持有者。"""

    __slots__ = ("_lock", "_snapshot")

    def __init__(self) -> None:
        self._lock: threading.Lock = threading.Lock()
        self._snapshot: dict[str, Any] | None = None

    def report(self, details: dict[str, Any]) -> None:
        """写入最新进度快照。

        :param details: 进度字段(例如 ``{"processed": 1200,
            "total": 50000, "checkpoint": "row=1200"}``)。必须是非 ``None``
            的 dict。敏感 key(password / secret / token / credential /
            apikey / ...)会抛 :class:`ValueError`。
        :raises ValueError: ``details`` 为 ``None``、非 dict,或包含像凭据的 key。

        存储副本与调用方 dict 解耦:调用后改 ``details`` 不影响快照,
        :meth:`latest` 后续消费方也无法通过返回值改动槽位。
        """
        # 防御性运行时检查 —— Python 类型注解只是建议;调用方可能仍传
        # None 或非 dict,即便注解是 ``dict[str, Any]``。
        if not isinstance(details, dict):
            raise ValueError(
                "ProgressReporter.report: details must be a non-None dict, "
                f"got {type(details).__name__}"
            )

        sensitive = find_sensitive_keys(details.keys())
        if sensitive:
            # 对齐 Java SensitiveDataValidator:拒绝,不记录值(否则失去意义)。
            raise ValueError(
                "ProgressReporter.report: details contains sensitive key(s) "
                f"{sensitive!r}; credentials must not be persisted in progress payloads"
            )

        snapshot = dict(details)  # 浅拷贝,防御调用方后续修改
        with self._lock:
            self._snapshot = snapshot

    def latest(self) -> dict[str, Any] | None:
        """返回最近一次快照;从未 report 过则返回 ``None``。

        返回的 dict 是**浅拷贝** —— 修改它不影响存储里的快照。从未调用
        :meth:`report` 时返回 ``None``(而非空 dict),对齐 Java SDK 的可空
        契约。
        """
        with self._lock:
            current = self._snapshot
        if current is None:
            return None
        return dict(current)
