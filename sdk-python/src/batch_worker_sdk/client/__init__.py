"""client 子包 —— 对齐 Java ``com.example.batch.sdk.client``。

包含 :class:`BatchPlatformClient` 入口类及其配置模型
:class:`BatchPlatformClientConfig`。
"""

from __future__ import annotations

from batch_worker_sdk.client.client import BatchPlatformClient
from batch_worker_sdk.client.config import BatchPlatformClientConfig

__all__: list[str] = [
    "BatchPlatformClient",
    "BatchPlatformClientConfig",
]
