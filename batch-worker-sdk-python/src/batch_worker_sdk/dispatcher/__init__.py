"""dispatcher 子包 —— 对齐 Java ``com.example.batch.sdk.dispatcher``。

承载 :class:`TaskDispatcher`(按租户维护 in-flight 注册表 + handler 路由)
以及 :func:`run_worker` 入口。
"""

from __future__ import annotations

from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher

__all__: list[str] = [
    "TaskDispatcher",
]
