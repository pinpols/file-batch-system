"""分片级断点续跑协议(ADR-037 决策一,P1)。

对齐 Java ``SdkCheckpoint`` / ``SdkCheckpointState``。断点的**语义由 SDK 定义,
持久化由 business 实现** —— SDK 不规定存到哪(租户控制表 / KV / 对象存储),
只要求实现读 / 写两个动作。

断点是**数据自身的主键 / 范围**,不是 offset:``break_position`` 记「已处理到
的记录主键 / 范围键」,续跑时从断点之后继续,而不是从头。

强约束(决策二)::meth:`SdkCheckpoint.save` **必须**与业务数据提交在**同一个
事务边界**内 —— 二者要么都成功要么都回滚,否则崩溃后断点与业务数据撕裂,
续跑不可靠。SDK 无法替租户实现跨存储事务,这条靠文档 + code review 把关。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable


@dataclass(frozen=True)
class SdkCheckpointState:
    """一次续跑任务的断点快照(对齐 Java ``SdkCheckpointState`` record)。

    :param break_position: 断点 —— 已处理到的记录主键 / 范围键(与切分键同坐标系)。
    :param succeed_count: 累计成功条数(续跑时恢复,不归零)。
    :param fail_count: 累计失败条数(续跑时恢复,不归零)。
    :param completed: 是否已跑完;``True`` 时续跑模板直接跳过(幂等)。
    """

    break_position: dict[str, Any] = field(default_factory=dict)
    succeed_count: int = 0
    fail_count: int = 0
    completed: bool = False

    def __post_init__(self) -> None:
        # 防御性拷贝 —— 调用方改原 dict 不污染快照。
        object.__setattr__(self, "break_position", dict(self.break_position or {}))


@runtime_checkable
class SdkCheckpoint(Protocol):
    """断点读 / 写 SPI(持久化由 business 实现)。"""

    def load(self, task_id: int) -> SdkCheckpointState | None:
        """启动时读回上次断点;首次运行返回 ``None``。"""
        ...

    def save(self, task_id: int, state: SdkCheckpointState) -> None:
        """保存断点。

        **强约束**:实现必须与业务数据提交在同一事务边界内(见决策二)。
        典型 JDBC 写法:同一个 connection 内先 ``update 断点行``(不单独 commit)
        再随业务 ``connection.commit()``,把两者合成一个事务。
        """
        ...


class InMemoryCheckpoint:
    """进程内断点存储默认实现(测试 / 单进程用)。

    **注意**:内存实现进程崩了断点就没了 —— 它只用于测试与本地。生产环境
    必须用与业务数据同事务的持久化实现(见下面 ``AsyncJdbcCheckpoint`` 文档示例)。
    """

    __slots__ = ("_states",)

    def __init__(self) -> None:
        self._states: dict[int, SdkCheckpointState] = {}

    def load(self, task_id: int) -> SdkCheckpointState | None:
        return self._states.get(task_id)

    def save(self, task_id: int, state: SdkCheckpointState) -> None:
        self._states[task_id] = state


# ---------------------------------------------------------------------------
# 文档示例:与业务数据同事务的 JDBC 等价实现(不在 SDK core 落地,仅示范)。
#
# 强约束的关键:断点行的 UPSERT 与业务写共用**同一个** connection / 事务,
# 由租户在 ``ctx.commit`` 的实现里把两件事串到一个 ``connection.commit()``。
#
#   class AsyncJdbcCheckpoint:
#       def __init__(self, pool):
#           self._pool = pool  # 租户自己的连接池(asyncpg / psycopg 等)
#
#       async def load(self, task_id: int) -> SdkCheckpointState | None:
#           row = await self._pool.fetchrow(
#               "SELECT break_position, succeed_count, fail_count, completed "
#               "FROM tenant_checkpoint WHERE task_id = $1",
#               task_id,
#           )
#           if row is None:
#               return None
#           return SdkCheckpointState(
#               break_position=json.loads(row["break_position"]),
#               succeed_count=row["succeed_count"],
#               fail_count=row["fail_count"],
#               completed=row["completed"],
#           )
#
#       async def save_in_txn(self, conn, task_id: int, state: SdkCheckpointState) -> None:
#           # 关键:conn 是业务批次写用的同一个连接/事务,调用方在写完业务数据
#           # 后、commit 前调本方法,二者随同一个 commit 落盘(同事务强约束)。
#           await conn.execute(
#               "INSERT INTO tenant_checkpoint(task_id, break_position, succeed_count, "
#               "fail_count, completed) VALUES ($1,$2,$3,$4,$5) "
#               "ON CONFLICT (task_id) DO UPDATE SET break_position=$2, succeed_count=$3, "
#               "fail_count=$4, completed=$5",
#               task_id, json.dumps(state.break_position), state.succeed_count,
#               state.fail_count, state.completed,
#           )
# ---------------------------------------------------------------------------


__all__ = [
    "InMemoryCheckpoint",
    "SdkCheckpoint",
    "SdkCheckpointState",
]
