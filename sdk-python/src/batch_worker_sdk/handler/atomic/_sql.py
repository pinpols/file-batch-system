"""SQL atomic handler — async port of Java ``SqlAtomicHandler``.

The Java version uses JDK ``java.sql.*``; the Python version uses
:mod:`asyncpg` (PostgreSQL only — matches the platform's data store).
``asyncpg`` is an **optional** dependency (extra ``[sql]``) — only
tenants that actually run SQL atoms need to install it.

To avoid a hard module-level import on a tenant who isn't using SQL,
the connection is supplied by the caller as a Protocol-shaped object:
this also makes the handler trivial to unit-test without a real DB.
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
    """Config for :class:`SqlAtomicHandler` (mirrors Java ``SqlAtomicConfig``).

    Attributes:
        task_type: Registered task-type code.
        statement_timeout_seconds: Per-statement query timeout.
        max_result_rows: Cap on rows returned per SELECT; excess marks
            ``truncated=True``.
        max_statements_per_job: Reject scripts with more ``;``-separated
            statements than this (matches platform ``SqlExecutorProperties``).
        default_auto_commit: ``False`` = explicit transaction (commit if
            all succeed, rollback on any error). ``True`` = each
            statement auto-commits.
        forbid_os_capable_role: Run the PG role probe before executing;
            reject if current_user has ``superuser`` /
            ``pg_execute_server_program`` / ``pg_read_server_files`` /
            ``pg_write_server_files``.
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
    """Protocol for an asyncpg-style PG connection.

    Mirrors the subset of :class:`asyncpg.Connection` we use; lets unit
    tests supply a hand-rolled fake without importing asyncpg.
    """

    async def fetch(self, query: str, *args: Any) -> list[Any]: ...
    async def execute(self, query: str, *args: Any) -> str: ...
    async def fetchval(self, query: str, *args: Any) -> Any: ...
    def transaction(self) -> Any: ...
    async def close(self) -> None: ...


@runtime_checkable
class SqlConnectionFactory(Protocol):
    """Async callable that hands out a fresh :class:`SqlConnection`."""

    async def __call__(self) -> SqlConnection: ...


_OS_CAPABLE_ROLE_PROBE = (
    "select rolsuper"
    " or pg_has_role(current_user, 'pg_execute_server_program', 'USAGE')"
    " or pg_has_role(current_user, 'pg_read_server_files', 'USAGE')"
    " or pg_has_role(current_user, 'pg_write_server_files', 'USAGE')"
    " from pg_roles where rolname = current_user"
)


class SqlAtomicHandler(SdkAbstractAtomicHandler):
    """Out-of-the-box SQL atomic handler (PG-only, asyncpg-backed).

    Parameters (from ``ctx.parameters``):

    * ``sql`` (str, required) — may contain ``;``-separated statements.

    Output:
    For SELECT (last result set) — ``{"resultSet": [...], "rowCount":
    n, "truncated": bool, "statementCount": k, "totalAffectedRows":
    a}``.

    For DML-only — ``{"statementCount": k, "affectedRows": a}``.
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


# ── statement splitting ───────────────────────────────────────────────────


def split_statements(sql: str) -> list[str]:  # noqa: PLR0912 — 1:1 mirror of Java state machine
    """Split a multi-statement SQL string on ``;`` honouring quotes / comments.

    Behaviour mirrors Java ``SqlAtomicHandler.splitStatements`` —
    simple, does **not** handle PG dollar-quoting (Java doesn't either).
    """
    out: list[str] = []
    buf: list[str] = []
    in_single = in_double = in_line = in_block = False
    prev = ""
    for c in sql:
        if in_line:
            if c == "\n":
                in_line = False
            buf.append(c)
        elif in_block:
            if prev == "*" and c == "/":
                in_block = False
            buf.append(c)
        elif in_single:
            if c == "'":
                in_single = False
            buf.append(c)
        elif in_double:
            if c == '"':
                in_double = False
            buf.append(c)
        elif prev == "-" and c == "-":
            in_line = True
            buf.append(c)
        elif prev == "/" and c == "*":
            in_block = True
            buf.append(c)
        elif c == "'":
            in_single = True
            buf.append(c)
        elif c == '"':
            in_double = True
            buf.append(c)
        elif c == ";":
            stmt = "".join(buf).strip()
            if stmt:
                out.append(stmt)
            buf.clear()
        else:
            buf.append(c)
        prev = c
    tail = "".join(buf).strip()
    if tail:
        out.append(tail)
    return out


_SELECT_RE = re.compile(r"^\s*(select|with|show|values)\b", re.IGNORECASE)


def _is_select(stmt: str) -> bool:
    return _SELECT_RE.match(stmt) is not None


_AFFECTED_RE = re.compile(r"(INSERT 0|UPDATE|DELETE|MERGE|COPY|MOVE)\s+(\d+)$", re.IGNORECASE)


def _parse_affected(command_tag: str) -> int:
    """Parse asyncpg's command tag (e.g. ``"UPDATE 3"``) for row count."""
    if not command_tag:
        return 0
    m = _AFFECTED_RE.search(command_tag.strip())
    if m:
        return int(m.group(2))
    return 0
