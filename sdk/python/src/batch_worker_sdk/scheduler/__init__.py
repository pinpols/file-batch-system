"""scheduler 子包 —— 对齐 Java ``com.example.batch.sdk.scheduler``。

承载 :class:`HeartbeatScheduler`、:class:`LeaseRenewalScheduler` 以及平台
directive 的解析逻辑。属于内部实现 —— 公开调用方应走
:class:`batch_worker_sdk.client.BatchPlatformClient`。
"""

from __future__ import annotations

__all__: list[str] = []
