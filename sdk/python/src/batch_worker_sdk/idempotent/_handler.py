"""声明式幂等的织入装饰器(对齐 Java ``SdkIdempotentHandler`` + ``@Idempotent``)。

包一个被 :func:`idempotent` 标注的 :class:`SdkTaskHandler`,在 ``execute``
前后织入去重:抢到 key → 执行,成功才回填;抢不到 → 取已记录结果短路。

为何用装饰器而非 AOP
--------------------
SDK core 禁引框架。Python 用类装饰器 :func:`idempotent` 在 handler 类上挂
``key`` / ``ttl_millis`` 元数据,再用 :func:`wrap_idempotent` 在注册时手工包
一层,零框架依赖。

原子去重(修掉 Java TOCTOU)
----------------------------
执行入口走 :meth:`SdkIdempotencyStore.try_acquire`(单步原子)而非
``find`` + ``record`` 这种 check-then-act:

- ``try_acquire`` 返回 ``True`` → 抢到执行权,执行;成功 → :meth:`record` 回填;
- ``try_acquire`` 返回 ``False`` → 已被占位 / 已有结果。再 :meth:`find` 取已记录
  结果:命中则短路返回;未命中(并发占位但还没回填)→ 视为冲突,返回
  :data:`IDEMPOTENT_IN_FLIGHT_CODE` 失败让平台重试(此次不是「已完成」,
  不能伪造成功)。
"""

from __future__ import annotations

import logging
from typing import TypeVar

from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.idempotent._entity import SdkIdempotencyEntity
from batch_worker_sdk.idempotent._key_resolver import resolve_key
from batch_worker_sdk.idempotent._store import SdkIdempotencyStore
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult

_log = logging.getLogger(__name__)

# 标注幂等的 handler 类上挂的元数据属性名。
_KEY_ATTR = "__sdk_idempotent_key__"
_TTL_ATTR = "__sdk_idempotent_ttl_millis__"

IDEMPOTENT_KEY_ERROR_CODE = "IDEMPOTENT_KEY_ERROR"
IDEMPOTENT_IN_FLIGHT_CODE = "IDEMPOTENT_IN_FLIGHT"

H = TypeVar("H", bound=type)


def idempotent(*, key: str, ttl_millis: int = 0):
    """类装饰器 —— 声明「同一业务键只执行一次」。

    :param key: 幂等键表达式(字面量 + ``{field}`` 占位符,见
        :func:`batch_worker_sdk.idempotent._key_resolver.resolve_key`)。必填。
    :param ttl_millis: 记录存活毫秒(``<= 0`` = 永久);透传给 store,由其决定语义。

    边界(红线):幂等是租户「自家业务表」侧的去重,SDK **不写**平台状态表。
    """
    if not key:
        raise ValueError("idempotent: key must be a non-empty template")

    def _decorate(cls: H) -> H:
        setattr(cls, _KEY_ATTR, key)
        setattr(cls, _TTL_ATTR, ttl_millis)
        return cls

    return _decorate


class SdkIdempotentHandler:
    """包一个被 :func:`idempotent` 标注的 handler,织入去重。

    透传 ``task_type`` / ``descriptor`` / ``cancel`` 给被包 handler;
    结构性满足 :class:`SdkTaskHandler` Protocol。
    """

    __slots__ = ("_delegate", "_key", "_store", "_ttl_millis")

    def __init__(
        self,
        delegate: SdkTaskHandler,
        store: SdkIdempotencyStore,
        key: str,
        ttl_millis: int,
    ) -> None:
        self._delegate = delegate
        self._store = store
        self._key = key
        self._ttl_millis = ttl_millis

    def task_type(self) -> str:
        return self._delegate.task_type()

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        return self._delegate.descriptor()

    def cancel(self, ctx: SdkTaskContext) -> None:
        self._delegate.cancel(ctx)

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        try:
            key = resolve_key(self._key, ctx)
        except ValueError as ex:
            return SdkTaskResult.fail(
                IDEMPOTENT_KEY_ERROR_CODE,
                f"idempotent key resolution failed for taskType={self.task_type()}: {ex}",
                cause=ex,
            )

        acquired = self._store.try_acquire(key, self._ttl_millis)
        if not acquired:
            existing = self._store.find(key)
            if existing is not None:
                _log.info(
                    "idempotent hit: taskType=%s key=%s — skipping execution",
                    self.task_type(),
                    key,
                )
                return existing.to_result()
            # 抢不到但又没回填结果 = 另一副本正在执行(in-flight)。
            # 不能伪造成功;返回失败让平台重试,待对方回填后下次命中。
            _log.info(
                "idempotent in-flight: taskType=%s key=%s — another execution holds the key",
                self.task_type(),
                key,
            )
            return SdkTaskResult.fail(
                IDEMPOTENT_IN_FLIGHT_CODE,
                f"idempotent key {key} is in-flight; retry after the holder completes",
            )

        try:
            result = await self._delegate.execute(ctx)
        except BaseException:
            # 业务抛异常:释放占位,让任务可重派重抢(对齐「失败不记录」)。
            self._store.release(key)
            raise
        if result.success:
            self._store.record(key, SdkIdempotencyEntity.of_result(result), self._ttl_millis)
        else:
            # 失败不记录,且释放占位 —— 否则重派会被自己的 in-flight 占位卡死。
            self._store.release(key)
        return result


def wrap_idempotent(
    delegate: SdkTaskHandler,
    store: SdkIdempotencyStore | None,
) -> SdkTaskHandler:
    """若 ``delegate`` 的类标了 :func:`idempotent` 则包装,否则原样返回。

    对齐 Java ``SdkIdempotentHandler.wrap``。命中注解但 ``store is None`` →
    fail-fast 抛 :class:`RuntimeError`(声明了幂等却没注入存储,属装配错误,
    越早暴露越好);无注解则不要求 store。
    """
    key = getattr(type(delegate), _KEY_ATTR, None)
    if key is None:
        return delegate
    if store is None:
        raise RuntimeError(
            f"handler taskType={delegate.task_type()} 声明了 @idempotent 但未注入 SdkIdempotencyStore"
        )
    ttl_millis = getattr(type(delegate), _TTL_ATTR, 0)
    return SdkIdempotentHandler(delegate, store, key, ttl_millis)


__all__ = [
    "IDEMPOTENT_IN_FLIGHT_CODE",
    "IDEMPOTENT_KEY_ERROR_CODE",
    "SdkIdempotentHandler",
    "idempotent",
    "wrap_idempotent",
]
