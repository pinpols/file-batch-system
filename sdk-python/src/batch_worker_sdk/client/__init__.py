"""Client subpackage — mirror of Java ``com.example.batch.sdk.client``.

Houses :class:`BatchPlatformClient` and its validated configuration model
:class:`BatchPlatformClientConfig`.
"""

from __future__ import annotations

from batch_worker_sdk.client.client import BatchPlatformClient
from batch_worker_sdk.client.config import BatchPlatformClientConfig

__all__: list[str] = [
    "BatchPlatformClient",
    "BatchPlatformClientConfig",
]
