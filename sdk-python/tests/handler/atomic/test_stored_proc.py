"""Unit tests for :mod:`batch_worker_sdk.handler.atomic._stored_proc`."""

from __future__ import annotations

from typing import Any

import pytest

from batch_worker_sdk.handler.atomic import (
    StoredProcAtomicConfig,
    StoredProcAtomicHandler,
)
from batch_worker_sdk.testkit import make_test_context

pytestmark = pytest.mark.asyncio


class _Txn:
    async def __aenter__(self) -> _Txn:
        return self

    async def __aexit__(self, exc_type, exc, tb) -> None:
        return None


class FakeConn:
    def __init__(
        self,
        *,
        call_rows: list[Any] | None = None,
        os_capable: bool = False,
        security_definer: bool = False,
        execute_priv: bool | None = None,
    ) -> None:
        self.call_rows = call_rows or []
        self.os_capable = os_capable
        self.security_definer = security_definer
        self.execute_priv = execute_priv
        self.executed_calls: list[str] = []
        self.closed = False

    async def fetch(self, query: str, *args: Any) -> list[Any]:
        self.executed_calls.append(query)
        return list(self.call_rows)

    async def execute(self, query: str, *args: Any) -> str:
        return "CALL"

    async def fetchval(self, query: str, *args: Any) -> Any:
        # Disambiguate by query content.
        q = query.lower()
        if "rolsuper" in q:
            return self.os_capable
        if "prosecdef" in q:
            return self.security_definer
        if "has_function_privilege" in q:
            return self.execute_priv
        return None

    def transaction(self) -> _Txn:
        return _Txn()

    async def close(self) -> None:
        self.closed = True


def _factory(conn: FakeConn):
    async def _build() -> FakeConn:
        return conn

    return _build


async def test_stored_proc_happy_path() -> None:
    conn = FakeConn(call_rows=[{"result_code": 0, "msg": "ok"}])
    config = StoredProcAtomicConfig(task_type="proc", forbid_os_capable_role=False)
    handler = StoredProcAtomicHandler(config, _factory(conn))
    ctx = make_test_context(
        parameters={"procedureName": "batch.refresh", "inParams": [1, "foo"]}
    )

    result = await handler._do_invoke(ctx)

    assert result["procedureName"] == "batch.refresh"
    assert result["outValues"] == {"p1": 0, "p2": "ok"}
    assert conn.executed_calls[0].startswith("CALL batch.refresh(")
    assert conn.closed is True


async def test_stored_proc_rejects_bad_procedure_name() -> None:
    conn = FakeConn()
    config = StoredProcAtomicConfig(task_type="proc", forbid_os_capable_role=False)
    handler = StoredProcAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"procedureName": "drop table users; --"})

    with pytest.raises(ValueError, match="procedureName must match"):
        await handler._do_invoke(ctx)


async def test_stored_proc_schema_allow_list_rejects_outsider() -> None:
    conn = FakeConn()
    config = StoredProcAtomicConfig(
        task_type="proc",
        allowed_schemas=frozenset({"batch"}),
        forbid_os_capable_role=False,
    )
    handler = StoredProcAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"procedureName": "evil.steal"})

    with pytest.raises(PermissionError, match="schema not allowed"):
        await handler._do_invoke(ctx)


async def test_stored_proc_blocks_security_definer() -> None:
    conn = FakeConn(security_definer=True)
    config = StoredProcAtomicConfig(task_type="proc", forbid_os_capable_role=False)
    handler = StoredProcAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"procedureName": "batch.dangerous"})

    with pytest.raises(PermissionError, match="SECURITY DEFINER"):
        await handler._do_invoke(ctx)


async def test_stored_proc_blocks_os_capable_role() -> None:
    conn = FakeConn(os_capable=True)
    config = StoredProcAtomicConfig(task_type="proc")
    handler = StoredProcAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"procedureName": "batch.refresh"})

    with pytest.raises(PermissionError, match="OS-capable DB role"):
        await handler._do_invoke(ctx)


async def test_stored_proc_execute_privilege_opt_in() -> None:
    conn = FakeConn(execute_priv=False)
    config = StoredProcAtomicConfig(
        task_type="proc",
        forbid_os_capable_role=False,
        verify_execute_privilege=True,
    )
    handler = StoredProcAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"procedureName": "batch.refresh"})

    with pytest.raises(PermissionError, match="lacks EXECUTE privilege"):
        await handler._do_invoke(ctx)


async def test_stored_proc_truncates_oversized_out_value() -> None:
    conn = FakeConn(call_rows=[{"big": "x" * 1000}])
    config = StoredProcAtomicConfig(
        task_type="proc",
        forbid_os_capable_role=False,
        max_out_bytes_per_param=10,
    )
    handler = StoredProcAtomicHandler(config, _factory(conn))
    ctx = make_test_context(parameters={"procedureName": "batch.dump"})

    result = await handler._do_invoke(ctx)
    out = result["outValues"]["p1"]
    assert isinstance(out, str)
    assert "truncated" in out
    assert out.startswith("xxxxxxxxxx")
