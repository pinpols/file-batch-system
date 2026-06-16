"""幂等去重子包 —— 对齐 Java ``com.example.batch.sdk.idempotent``。

声明式幂等:在 handler 类上标 :func:`idempotent`,注册时用
:func:`wrap_idempotent` 包一层。去重存储由租户注入 :class:`SdkIdempotencyStore`
SPI 实现(SDK core 不硬编码 JDBC / Redis),核心原语
:meth:`SdkIdempotencyStore.try_acquire` 是**原子单步**(修掉 Java
find-then-record 的 TOCTOU)。
"""

from __future__ import annotations

from batch_worker_sdk.idempotent._entity import SdkIdempotencyEntity
from batch_worker_sdk.idempotent._handler import (
    IDEMPOTENT_IN_FLIGHT_CODE,
    IDEMPOTENT_KEY_ERROR_CODE,
    SdkIdempotentHandler,
    idempotent,
    wrap_idempotent,
)
from batch_worker_sdk.idempotent._key_resolver import resolve_key
from batch_worker_sdk.idempotent._store import (
    InMemoryIdempotencyStore,
    NoOpIdempotencyStore,
    SdkIdempotencyStore,
)

__all__ = [
    "IDEMPOTENT_IN_FLIGHT_CODE",
    "IDEMPOTENT_KEY_ERROR_CODE",
    "InMemoryIdempotencyStore",
    "NoOpIdempotencyStore",
    "SdkIdempotencyEntity",
    "SdkIdempotencyStore",
    "SdkIdempotentHandler",
    "idempotent",
    "resolve_key",
    "wrap_idempotent",
]
