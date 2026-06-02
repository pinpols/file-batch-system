"""Unit tests for :mod:`batch_worker_sdk.handler.atomic._sql`.

Uses a hand-rolled fake :class:`SqlConnection` instead of asyncpg so
the tests run without a Postgres dependency / wheel install.
"""

from __future__ import annotations

from typing import Any

import pytest

from batch_worker_sdk.handler.atomic import SqlAtomicConfig, SqlAtomicHandler
from batch_worker_sdk.handler.atomic._sql import split_statements
from batch_worker_sdk.testkit import make_test_context

pytestmark = pytest.mark.asyncio


class FakeTransaction:
    def __init__(self, conn: FakeConn) -> None:
        self._conn = conn

    async def __aenter__(self) -> FakeTransaction:
        self._conn.txn_open = True
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        self._conn.txn_open = False
        self._conn.txn_committed = exc_type is None


class FakeConn:
    """asyncpg-compatible fake; programmed via attributes."""

    def __init__(
        self,
        *,
        fetch_rows: list[Any] | None = None,
        execute_tag: str = "UPDATE 0",
        os_capable: bool = False,
    ) -> None:
        self.fetch_rows = fetch_rows or []
        self.execute_tag = execute_tag
        self.os_capable = os_capable
        self.executed: list[str] = []
        self.fetched: list[str] = []
        self.txn_open = False
        self.txn_committed = False
        self.closed = False

    async def fetch(self, query: str, *args: Any) -> list[Any]:
        self.fetched.append(query)
        return list(self.fetch_rows)

    async def execute(self, query: str, *args: Any) -> str:
        self.executed.append(query)
        return self.execute_tag

    async def fetchval(self, query: str, *args: Any) -> Any:
        # Used by OS-capable role probe.
        return self.os_capable

    def transaction(self) -> FakeTransaction:
        return FakeTransaction(self)

    async def close(self) -> None:
        self.closed = True


def _factory(conn: FakeConn):
    async def _build() -> FakeConn:
        return conn

    return _build


async def test_sql_select_returns_result_set() -> None:
    conn = FakeConn(fetch_rows=[{"id": 1, "name": "alice"}, {"id": 2, "name": "bob"}])
    config = SqlAtomicConfig(task_type="sql", forbid_os_capable_role=False)
    handler = SqlAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"sql": "SELECT id, name FROM users"})

    result = await handler._do_invoke(ctx)

    assert result["rowCount"] == 2
    assert result["truncated"] is False
    assert result["statementCount"] == 1
    assert result["resultSet"][0]["name"] == "alice"
    assert conn.closed is True


async def test_sql_dml_returns_affected_rows_and_commits() -> None:
    conn = FakeConn(execute_tag="UPDATE 3")
    config = SqlAtomicConfig(task_type="sql", forbid_os_capable_role=False)
    handler = SqlAtomicHandler(config, _factory(conn))
    ctx = make_test_context(
        parameters={
            "sql": "UPDATE users SET name='x' WHERE id=1; UPDATE users SET name='y' WHERE id=2;"
        }
    )

    result = await handler._do_invoke(ctx)

    assert result == {"statementCount": 2, "affectedRows": 6}
    assert conn.txn_committed is True


async def test_sql_truncates_oversized_result_set() -> None:
    conn = FakeConn(fetch_rows=[{"id": i} for i in range(50)])
    config = SqlAtomicConfig(task_type="sql", forbid_os_capable_role=False, max_result_rows=10)
    handler = SqlAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"sql": "SELECT id FROM users"})

    result = await handler._do_invoke(ctx)

    assert result["rowCount"] == 10
    assert result["truncated"] is True


async def test_sql_rejects_too_many_statements() -> None:
    conn = FakeConn()
    config = SqlAtomicConfig(
        task_type="sql", forbid_os_capable_role=False, max_statements_per_job=2
    )
    handler = SqlAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"sql": "SELECT 1; SELECT 2; SELECT 3;"})

    with pytest.raises(ValueError, match="too many statements"):
        await handler._do_invoke(ctx)


async def test_sql_forbids_os_capable_role() -> None:
    conn = FakeConn(os_capable=True)
    config = SqlAtomicConfig(task_type="sql")  # forbid_os_capable_role=True
    handler = SqlAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"sql": "SELECT 1"})

    with pytest.raises(PermissionError, match="OS-capable DB role"):
        await handler._do_invoke(ctx)


async def test_sql_propagates_exception_through_handler_execute() -> None:
    conn = FakeConn(os_capable=True)
    config = SqlAtomicConfig(task_type="sql")
    handler = SqlAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"sql": "SELECT 1"})

    result = await handler.execute(ctx)
    assert result.success is False
    assert "OS-capable" in (result.message or "")


async def test_split_statements_honours_quotes_and_comments() -> None:
    # ``;`` inside single-quoted literal must not split; ``;`` inside
    # a ``-- line comment`` must not split (the comment stays attached
    # to the following statement, same as the Java implementation).
    sql = "SELECT ';' AS x;\nUPDATE t SET v=1;"
    stmts = split_statements(sql)
    assert len(stmts) == 2
    assert stmts[0] == "SELECT ';' AS x"
    assert stmts[1] == "UPDATE t SET v=1"

    sql_with_comment = "SELECT 1;-- ; semicolon inside line comment\nUPDATE t SET v=1;"
    stmts2 = split_statements(sql_with_comment)
    assert len(stmts2) == 2
    assert stmts2[0] == "SELECT 1"
    assert "UPDATE" in stmts2[1]
