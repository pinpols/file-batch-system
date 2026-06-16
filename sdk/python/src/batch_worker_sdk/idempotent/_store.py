"""幂等去重存储 SPI(对齐 Java ``SdkIdempotencyStore``,并修掉其 TOCTOU)。

由 SDK 接入方(租户)注入实现,存进自家业务库 / Redis。

红线
----
实现**只能**写租户自己的存储,**禁**碰平台 ``job_instance`` /
``outbox_event`` 等状态表(orchestrator 是唯一状态主机)。SDK core 不提供
JDBC / Spring 默认实现,仅给 :class:`InMemoryIdempotencyStore`(测试用)和
:class:`NoOpIdempotencyStore`(永不去重,等价关闭)。

为什么是 ``try_acquire`` 而非 ``find`` + ``record``
---------------------------------------------------
Java 版用 ``find`` 命中?→ 跳过;未命中 → 执行后 ``record``。Java code
review 指出:**``find`` 与 ``record`` 之间是 check-then-act,两个并发 redelivery
会双双 miss → 双双执行**(TOCTOU)。Python 版把去重原子化成单步
:meth:`try_acquire`:

- 返回 ``True`` = 本调用方**抢到**了该 key 的执行权(此前无成功记录),应执行;
- 返回 ``False`` = 已有他方占位 / 已有成功记录,应短路。

实现**必须**用底层存储的原子原语保证 ``try_acquire`` 互斥,典型做法:

- SQL: ``INSERT ... ON CONFLICT (key) DO NOTHING`` + 看影响行数 / ``RETURNING``;
- Redis: ``SET key val NX [PX ttl]``;
- 任何带 UNIQUE 约束的表:``INSERT`` 命中唯一冲突即 ``False``。

绝不能实现成「先 SELECT 再 INSERT」—— 那等于把 TOCTOU 搬进了实现里。

生命周期(三态)
----------------
1. :meth:`try_acquire` 抢位:抢到则插入「执行中」占位行(无结果);
2. 执行成功 → :meth:`record` 把结果回填到占位行;
3. :meth:`find` 命中**已回填结果**的行 → 返回快照(命中跳过)。

占位但未回填(执行中崩溃)的行由实现据 ``ttl`` 过期回收,使任务可重派重抢。
"""

from __future__ import annotations

import threading
from typing import Protocol, runtime_checkable

from batch_worker_sdk.idempotent._entity import SdkIdempotencyEntity


@runtime_checkable
class SdkIdempotencyStore(Protocol):
    """租户注入的去重存储协议。"""

    def try_acquire(self, key: str, ttl_millis: int = 0) -> bool:
        """原子地为 ``key`` 抢执行权。

        :returns: ``True`` = 抢到(此前无记录,应执行);``False`` = 已占位 /
            已有成功结果(应短路,随后 :meth:`find` 取已记录结果)。
        :param ttl_millis: 占位/记录存活毫秒(``<= 0`` = 永久);实现据此回收
            执行中崩溃留下的占位行。

        **实现必须**用底层原子原语(``INSERT ON CONFLICT`` / ``SET NX`` /
        UNIQUE 冲突)保证互斥,严禁 SELECT-then-INSERT。
        """
        ...

    def find(self, key: str) -> SdkIdempotencyEntity | None:
        """查 ``key`` 是否已有**已回填结果**的成功记录。

        命中(回填过结果)→ 快照;未命中 / 仅占位未回填 → ``None``。
        """
        ...

    def record(self, key: str, entity: SdkIdempotencyEntity, ttl_millis: int = 0) -> None:
        """把一次成功结果回填到 ``key`` 的占位行。"""
        ...

    def release(self, key: str) -> None:
        """释放一个**未回填结果**的占位(执行失败 → 让任务可重抢)。

        实现据底层存储删除占位行;已回填结果的 key 应保持不动(成功记录是
        终态)。若占位已不存在则为 no-op。
        """
        ...


class NoOpIdempotencyStore:
    """永不去重 —— :meth:`try_acquire` 恒 ``True``、:meth:`find` 恒 ``None``。

    等价关闭幂等(本地 / 测试占位)。对齐 Java ``SdkIdempotencyStore.NoOp``。
    """

    def try_acquire(self, key: str, ttl_millis: int = 0) -> bool:
        return True

    def find(self, key: str) -> SdkIdempotencyEntity | None:
        return None

    def record(self, key: str, entity: SdkIdempotencyEntity, ttl_millis: int = 0) -> None:
        return None

    def release(self, key: str) -> None:
        return None


class InMemoryIdempotencyStore:
    """线程安全的进程内默认实现(测试 / 单进程用)。

    用一把 :class:`threading.Lock` 把 ``try_acquire`` 的「查占位 + 插占位」
    合成原子步,**演示**生产实现必须保证的互斥语义(生产环境换成 DB UNIQUE /
    Redis NX)。``ttl_millis`` 在内存实现里**不**做过期回收 —— 进程内去重不需要,
    文档化为已知简化。
    """

    __slots__ = ("_lock", "_placeholders", "_records")

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # 抢到执行权但尚未回填结果的 key(执行中占位)。
        self._placeholders: set[str] = set()
        # 已回填成功结果的 key。
        self._records: dict[str, SdkIdempotencyEntity] = {}

    def try_acquire(self, key: str, ttl_millis: int = 0) -> bool:
        with self._lock:
            if key in self._placeholders or key in self._records:
                return False
            self._placeholders.add(key)
            return True

    def find(self, key: str) -> SdkIdempotencyEntity | None:
        with self._lock:
            return self._records.get(key)

    def record(self, key: str, entity: SdkIdempotencyEntity, ttl_millis: int = 0) -> None:
        with self._lock:
            self._records[key] = entity
            self._placeholders.discard(key)

    def release(self, key: str) -> None:
        with self._lock:
            # 只释放占位;已回填结果是终态,不动。
            self._placeholders.discard(key)


__all__ = [
    "InMemoryIdempotencyStore",
    "NoOpIdempotencyStore",
    "SdkIdempotencyStore",
]
