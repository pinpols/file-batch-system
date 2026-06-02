"""Stored-procedure atomic handler — async port of Java ``StoredProcAtomicHandler``.

Implements the same four security gates as the Java SDK:

1. **forbid_os_capable_role** — reject ``superuser`` /
   ``pg_execute_server_program`` / ``pg_read_server_files`` /
   ``pg_write_server_files`` (dual-use RCE isolation).
2. **allowed_schemas** — schema must be in the allow-list when set.
3. **allow_security_definer** — when false, reject procedures with
   ``pg_proc.prosecdef = true`` (privilege-escalation risk).
4. **verify_execute_privilege** (opt-in) — current_user must have
   EXECUTE on the procedure.

Uses :mod:`asyncpg`-style connections; supplied by the caller via a
factory (same pattern as :class:`SqlAtomicHandler`).
"""

from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from typing import Any

from batch_worker_sdk.handler._atomic import SdkAbstractAtomicHandler
from batch_worker_sdk.handler.atomic._sql import SqlConnection, SqlConnectionFactory
from batch_worker_sdk.task.context import SdkTaskContext

_LOG = logging.getLogger(__name__)

_PROC_NAME_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)?$")


@dataclass(frozen=True)
class StoredProcAtomicConfig:
    """Config for :class:`StoredProcAtomicHandler` (mirrors Java
    ``StoredProcAtomicConfig``).

    Attributes:
        task_type: Registered task-type code.
        allowed_schemas: Schema allow-list. Empty = dev-only full allow.
        allow_security_definer: Allow ``SECURITY DEFINER`` procedures.
        forbid_os_capable_role: Run the PG role probe.
        verify_execute_privilege: Opt-in EXECUTE-privilege check.
        statement_timeout_seconds: CALL query timeout.
        max_out_bytes_per_param: Per-OUT-param string byte cap.
        default_auto_commit: Transaction mode.
    """

    task_type: str
    allowed_schemas: frozenset[str] = field(default_factory=frozenset)
    allow_security_definer: bool = False
    forbid_os_capable_role: bool = True
    verify_execute_privilege: bool = False
    statement_timeout_seconds: int = 60
    max_out_bytes_per_param: int = 64 * 1024
    default_auto_commit: bool = False

    def __post_init__(self) -> None:
        if not self.task_type or not self.task_type.strip():
            raise ValueError("task_type must not be blank")
        if self.statement_timeout_seconds <= 0:
            raise ValueError("statement_timeout_seconds must be > 0")
        if self.max_out_bytes_per_param <= 0:
            raise ValueError("max_out_bytes_per_param must be > 0")
        object.__setattr__(self, "allowed_schemas", frozenset(self.allowed_schemas))

    @classmethod
    def defaults(cls, task_type: str) -> StoredProcAtomicConfig:
        return cls(task_type=task_type)


_OS_CAPABLE_ROLE_PROBE = (
    "select rolsuper"
    " or pg_has_role(current_user, 'pg_execute_server_program', 'USAGE')"
    " or pg_has_role(current_user, 'pg_read_server_files', 'USAGE')"
    " or pg_has_role(current_user, 'pg_write_server_files', 'USAGE')"
    " from pg_roles where rolname = current_user"
)


class StoredProcAtomicHandler(SdkAbstractAtomicHandler):
    """Out-of-the-box PG stored-procedure atomic handler.

    Parameters (from ``ctx.parameters``):

    * ``procedureName`` (str, required) — schema-qualified or bare
      identifier, validated against ``^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$``.
    * ``inParams`` (list, optional) — IN args, positional.
    * ``outParams`` (list[str], optional) — OUT type names (reserved
      for future use; included for parity with Java).

    Output: ``{"outValues": {"p1": ..., ...}, "procedureName": name}``.
    """

    def __init__(
        self,
        config: StoredProcAtomicConfig,
        connection_factory: SqlConnectionFactory,
    ) -> None:
        if config is None:
            raise ValueError("config must not be None")
        if connection_factory is None:
            raise ValueError("connection_factory must not be None")
        self._config = config
        self._connection_factory = connection_factory

    def task_type(self) -> str:
        return self._config.task_type

    async def _do_invoke(self, ctx: SdkTaskContext) -> dict[str, Any]:
        params = ctx.parameters
        proc_name = _parse_procedure_name(params)
        in_params = _parse_in_params(params.get("inParams"))
        out_types = _parse_out_types(params.get("outParams"))

        # Gate 2 — allow-list check before opening a connection.
        self._require_allowed_schema(proc_name)

        conn = await self._connection_factory()
        try:
            if self._config.forbid_os_capable_role:
                await self._require_non_os_capable_role(conn)
            if not self._config.allow_security_definer:
                await self._require_not_security_definer(conn, proc_name)
            if self._config.verify_execute_privilege:
                await self._require_execute_privilege(conn, proc_name)
            return await self._run_call(conn, proc_name, in_params, out_types)
        finally:
            try:
                await conn.close()
            except Exception as exc:
                _LOG.warning("connection close failed: %s", exc)

    async def _run_call(
        self,
        conn: SqlConnection,
        proc_name: str,
        in_params: list[Any],
        out_types: list[str],
    ) -> dict[str, Any]:
        # PG procedures: ``CALL schema.proc($1, $2, ...)``. OUT params
        # on PG are columns of the returned row (asyncpg ``fetch``
        # surfaces them); IN-only procedures still go through ``fetch``
        # so we get OUT values when present.
        placeholders = ", ".join(f"${i + 1}" for i in range(len(in_params)))
        call_sql = f"CALL {proc_name}({placeholders})"

        async def _do() -> list[Any]:
            return await conn.fetch(call_sql, *in_params)

        if self._config.default_auto_commit:
            rows = await _do()
        else:
            async with conn.transaction():
                rows = await _do()

        out_values: dict[str, Any] = {}
        if rows:
            row = rows[0]
            try:
                row_map = dict(row)
            except (TypeError, ValueError):
                row_map = {}
            for i, (_key, value) in enumerate(row_map.items()):
                # Java exposes OUT params as ``p1``, ``p2``... — keep
                # that name in the public output for cross-language
                # parity, but include the real column name as a hint.
                out_values[f"p{i + 1}"] = self._truncate_out(value)
        _ = out_types  # currently advisory only; kept for Java-parity API
        _LOG.info(
            "stored proc %s called (in=%d, out=%d)",
            proc_name,
            len(in_params),
            len(out_values),
        )
        return {"outValues": out_values, "procedureName": proc_name}

    # ── gates ─────────────────────────────────────────────────────────────

    def _require_allowed_schema(self, proc_name: str) -> None:
        if not self._config.allowed_schemas:
            return
        schema = _schema_of(proc_name)
        if schema is None or schema not in self._config.allowed_schemas:
            raise PermissionError(
                f"procedureName schema not allowed: {proc_name}, "
                f"allowed_schemas={sorted(self._config.allowed_schemas)}"
            )

    async def _require_non_os_capable_role(self, conn: SqlConnection) -> None:
        flagged = await conn.fetchval(_OS_CAPABLE_ROLE_PROBE)
        if flagged:
            raise PermissionError(
                "refusing stored-proc on OS-capable DB role (superuser / "
                "pg_execute_server_program / pg_read_server_files / "
                "pg_write_server_files)"
            )

    async def _require_not_security_definer(
        self, conn: SqlConnection, proc_name: str
    ) -> None:
        schema = _schema_of(proc_name)
        name = _name_of(proc_name)
        if schema is None:
            sql = "select prosecdef from pg_proc where proname = $1"
            args: tuple[Any, ...] = (name,)
        else:
            sql = (
                "select p.prosecdef from pg_catalog.pg_proc p"
                " join pg_catalog.pg_namespace n on n.oid = p.pronamespace"
                " where p.proname = $1 and n.nspname = $2"
            )
            args = (name, schema)
        flagged = await conn.fetchval(sql, *args)
        if flagged:
            raise PermissionError(
                f"refusing SECURITY DEFINER procedure (privilege escalation risk): {proc_name}"
            )

    async def _require_execute_privilege(
        self, conn: SqlConnection, proc_name: str
    ) -> None:
        sql = "select has_function_privilege(current_user, $1, 'EXECUTE')"
        ok = await conn.fetchval(sql, proc_name)
        if ok is False:
            raise PermissionError(
                f"current_user lacks EXECUTE privilege on procedure: {proc_name}"
            )

    # ── helpers ───────────────────────────────────────────────────────────

    def _truncate_out(self, value: Any) -> Any:
        if not isinstance(value, str):
            return value
        encoded = value.encode("utf-8")
        cap = self._config.max_out_bytes_per_param
        if len(encoded) <= cap:
            return value
        head = encoded[:cap].decode("utf-8", errors="replace")
        return f"{head}...[truncated {len(encoded)} bytes]"


# ── parsing helpers ───────────────────────────────────────────────────────


def _parse_procedure_name(params: dict[str, Any]) -> str:
    raw = params.get("procedureName")
    if not isinstance(raw, str) or not raw.strip():
        raise ValueError("parameters.procedureName required")
    proc = raw.strip()
    if not _PROC_NAME_RE.match(proc):
        raise ValueError(
            "procedureName must match "
            "^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?$, got: " + proc
        )
    return proc


def _parse_in_params(raw: Any) -> list[Any]:
    if raw is None:
        return []
    if not isinstance(raw, list):
        raise ValueError("parameters.inParams must be a list")
    return list(raw)


def _parse_out_types(raw: Any) -> list[str]:
    if raw is None:
        return []
    if not isinstance(raw, list):
        raise ValueError("parameters.outParams must be a list of SQL type names")
    out: list[str] = []
    for o in raw:
        if o is None:
            raise ValueError("parameters.outParams contains null")
        out.append(str(o).strip().upper())
    return out


def _schema_of(proc_name: str) -> str | None:
    dot = proc_name.find(".")
    return proc_name[:dot] if dot > 0 else None


def _name_of(proc_name: str) -> str:
    dot = proc_name.find(".")
    return proc_name[dot + 1 :] if dot > 0 else proc_name
