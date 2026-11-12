"""SQL atomic handler —— Java ``SqlAtomicHandler`` 的异步移植。

Java 版基于 JDK ``java.sql.*``;Python 版基于 :mod:`asyncpg`
(仅 PostgreSQL —— 与平台数据存储一致)。``asyncpg`` 是**可选**依赖
(extra ``[sql]``)—— 只有真正跑 SQL atom 的租户才需要安装。

为避免不用 SQL 的租户在模块级被硬 import,连接由调用方按 Protocol
形状传入:这同时让 handler 不依赖真实库即可单测。
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable

from batch_worker_sdk.handler._atomic import SdkAbstractAtomicHandler
from batch_worker_sdk.task.context import SdkTaskContext

_LOG = logging.getLogger(__name__)


@dataclass(frozen=True)
class SqlAtomicConfig:
    """:class:`SqlAtomicHandler` 的配置(对齐 Java ``SqlAtomicConfig``)。

    Attributes:
        task_type: 注册的任务类型码。
        statement_timeout_seconds: 单条语句查询超时。
        max_result_rows: 每个 SELECT 返回行上限,超出标记
            ``truncated=True``。
        max_statements_per_job: ``;`` 分隔语句数上限,超出直接拒绝
            (对齐平台 ``SqlExecutorProperties``)。
        default_auto_commit: ``False`` = 显式事务(全成功 commit、任一
            失败 rollback);``True`` = 每条语句自动 commit。
        forbid_os_capable_role: 执行前跑 PG 角色探测,current_user 若拥有
            ``superuser`` / ``pg_execute_server_program`` /
            ``pg_read_server_files`` / ``pg_write_server_files`` 则拒绝。
    """

    task_type: str
    statement_timeout_seconds: int = 30
    max_result_rows: int = 10000
    max_statements_per_job: int = 50
    default_auto_commit: bool = False
    forbid_os_capable_role: bool = True

    def __post_init__(self) -> None:
        if not self.task_type or not self.task_type.strip():
            raise ValueError("task_type must not be blank")
        if self.statement_timeout_seconds <= 0:
            raise ValueError("statement_timeout_seconds must be > 0")
        if self.max_result_rows <= 0:
            raise ValueError("max_result_rows must be > 0")
        if self.max_statements_per_job <= 0:
            raise ValueError("max_statements_per_job must be > 0")

    @classmethod
    def defaults(cls, task_type: str) -> SqlAtomicConfig:
        return cls(task_type=task_type)


@runtime_checkable
class SqlConnection(Protocol):
    """asyncpg 风格 PG 连接的 Protocol。

    只对齐我们用到的 :class:`asyncpg.Connection` 子集;让单测无需引入
    asyncpg 即可手写假对象。
    """

    async def fetch(self, query: str, *args: Any) -> list[Any]: ...
    async def execute(self, query: str, *args: Any) -> str: ...
    async def fetchval(self, query: str, *args: Any) -> Any: ...
    def transaction(self) -> Any: ...
    async def close(self) -> None: ...


@runtime_checkable
class SqlConnectionFactory(Protocol):
    """每次产出一个新 :class:`SqlConnection` 的异步可调用对象。"""

    async def __call__(self) -> SqlConnection: ...


_OS_CAPABLE_ROLE_PROBE = (
    "select rolsuper"
    " or pg_has_role(current_user, 'pg_execute_server_program', 'USAGE')"
    " or pg_has_role(current_user, 'pg_read_server_files', 'USAGE')"
    " or pg_has_role(current_user, 'pg_write_server_files', 'USAGE')"
    " from pg_roles where rolname = current_user"
)


class SqlAtomicHandler(SdkAbstractAtomicHandler):
    """开箱即用的 SQL atomic handler(仅 PG,基于 asyncpg)。

    参数(来自 ``ctx.parameters``):

    * ``sql`` (str,必填)—— 可以是多条 ``;`` 分隔语句。

    输出:
    SELECT(取最后一个结果集)—— ``{"resultSet": [...], "rowCount":
    n, "truncated": bool, "statementCount": k, "totalAffectedRows":
    a}``。

    纯 DML —— ``{"statementCount": k, "affectedRows": a}``。
    """

    def __init__(self, config: SqlAtomicConfig, connection_factory: SqlConnectionFactory) -> None:
        if config is None:
            raise ValueError("config must not be None")
        if connection_factory is None:
            raise ValueError("connection_factory must not be None")
        self._config = config
        self._connection_factory = connection_factory

    def task_type(self) -> str:
        return self._config.task_type

    async def _do_invoke(self, ctx: SdkTaskContext) -> dict[str, Any]:
        sql_raw = ctx.parameters.get("sql")
        if not isinstance(sql_raw, str) or not sql_raw.strip():
            raise ValueError("missing required parameter 'sql'")
        statements = split_statements(sql_raw)
        if not statements:
            raise ValueError("no executable SQL statement found")
        if len(statements) > self._config.max_statements_per_job:
            raise ValueError(
                f"too many statements: {len(statements)} > "
                f"max_statements_per_job={self._config.max_statements_per_job}"
            )

        conn = await self._connection_factory()
        try:
            if self._config.forbid_os_capable_role:
                await self._assert_not_os_capable_role(conn)
            return await self._run_statements(conn, statements)
        finally:
            try:
                await conn.close()
            except Exception as exc:
                _LOG.warning("connection close failed: %s", exc)

    async def _run_statements(self, conn: SqlConnection, statements: list[str]) -> dict[str, Any]:
        last_result_set: dict[str, Any] | None = None
        total_affected = 0

        async def _execute_all() -> None:
            nonlocal total_affected, last_result_set
            for stmt in statements:
                if _is_select(stmt):
                    rows = await conn.fetch(stmt)
                    last_result_set = self._format_rows(rows)
                else:
                    tag = await conn.execute(stmt)
                    total_affected += _parse_affected(tag)

        if self._config.default_auto_commit:
            await _execute_all()
        else:
            async with conn.transaction():
                await _execute_all()

        if last_result_set is not None:
            last_result_set["statementCount"] = len(statements)
            last_result_set["totalAffectedRows"] = total_affected
            return last_result_set
        return {"statementCount": len(statements), "affectedRows": total_affected}

    def _format_rows(self, rows: list[Any]) -> dict[str, Any]:
        cap = self._config.max_result_rows
        truncated = len(rows) > cap
        if truncated:
            rows = rows[:cap]
        out_rows = [dict(r) for r in rows]
        return {"resultSet": out_rows, "rowCount": len(out_rows), "truncated": truncated}

    async def _assert_not_os_capable_role(self, conn: SqlConnection) -> None:
        flagged = await conn.fetchval(_OS_CAPABLE_ROLE_PROBE)
        if flagged:
            raise PermissionError("refusing SQL on OS-capable DB role")


# ── 语句切分 ──────────────────────────────────────────────────────────────


def split_statements(sql: str) -> list[str]:
    """按 ``;`` 切多语句 SQL,正确处理引号 / 注释 / PG dollar-quote。

    行为对齐 Java ``SqlAtomicHandler.splitStatements`` 与平台 ``SqlTaskExecutor``:
    顶层 ``$tag$ ... $tag$`` 体内的 ``;`` 不切,``$1`` 位置参数不是开标签。
    """
    state = _SplitState()
    i = 0
    while i < len(sql):
        c = sql[i]
        tag = _match_dollar_tag(sql, i) if c == "$" and state.at_code() else None
        if tag is not None:
            i = state.feed_dollar_quoted(sql, i, tag)
            continue
        state.feed(c)
        i += 1
    return state.complete()


class _SplitState:
    """引号、注释与前一个字符属于同一词法状态;只有普通 SQL 区域的 ``;`` 结束语句。"""

    def __init__(self) -> None:
        self._out: list[str] = []
        self._buf: list[str] = []
        self._lex = "code"  # code | line | block | single | double
        self._prev = ""

    def at_code(self) -> bool:
        return self._lex == "code"

    def feed(self, c: str) -> None:
        if self._lex == "line":
            if c == "\n":
                self._lex = "code"
            self._buf.append(c)
        elif self._lex == "block":
            if self._prev == "*" and c == "/":
                self._lex = "code"
            self._buf.append(c)
        elif self._lex == "single":
            if c == "'":
                self._lex = "code"
            self._buf.append(c)
        elif self._lex == "double":
            if c == '"':
                self._lex = "code"
            self._buf.append(c)
        else:
            self._feed_code(c)
        self._prev = c

    def _feed_code(self, c: str) -> None:
        if self._prev == "-" and c == "-":
            self._lex = "line"
        elif self._prev == "/" and c == "*":
            self._lex = "block"
        elif c == "'":
            self._lex = "single"
        elif c == '"':
            self._lex = "double"
        elif c == ";":
            self._flush()
            return
        self._buf.append(c)

    def feed_dollar_quoted(self, sql: str, start: int, tag: str) -> int:
        """整段写入 dollar-quote(含开/闭标签),返回闭标签之后的索引;未闭合时写到串尾。"""
        close = sql.find(tag, start + len(tag))
        end = len(sql) if close < 0 else close + len(tag)
        self._buf.append(sql[start:end])
        self._prev = "$"
        return end

    def _flush(self) -> None:
        stmt = "".join(self._buf).strip()
        if stmt:
            self._out.append(stmt)
        self._buf.clear()

    def complete(self) -> list[str]:
        self._flush()
        return self._out


def _match_dollar_tag(sql: str, start: int) -> str | None:
    """返回 ``start`` 处的 dollar-quote 开标签(含两端 ``$``);不是合法标签时返回 None。"""
    for j in range(start + 1, len(sql)):
        c = sql[j]
        if c == "$":
            tag = sql[start + 1 : j]
            return sql[start : j + 1] if not tag or not tag[0].isdigit() else None
        if not (c.isalnum() or c == "_"):
            return None
    return None


_SELECT_RE = re.compile(r"^\s*(select|with|show|values)\b", re.IGNORECASE)


def _is_select(stmt: str) -> bool:
    return _SELECT_RE.match(stmt) is not None


_AFFECTED_RE = re.compile(r"(INSERT 0|UPDATE|DELETE|MERGE|COPY|MOVE)\s+(\d+)$", re.IGNORECASE)


def _parse_affected(command_tag: str) -> int:
    """从 asyncpg 的 command tag(如 ``"UPDATE 3"``)解析行数。"""
    if not command_tag:
        return 0
    normalized_command_tag = command_tag.strip()
    m = _AFFECTED_RE.search(normalized_command_tag)
    if m:
        affected_rows_text = m.group(2)
        return int(affected_rows_text)
    return 0
