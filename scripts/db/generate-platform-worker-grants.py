#!/usr/bin/env python3
# 用途：从内建 Worker 的 Mapper XML 与内联 SQL 推导平台库最小授权，生成授权清单与 psql 授权脚本（ARCH-20261103-01）。
# 用法：python3 scripts/db/generate-platform-worker-grants.py [--write]；默认只校验已提交产物与源码一致，不一致时退出 1。
"""Worker 平台库授权清单生成器。

每个 Worker 的权限 = worker-core 公共 SQL ∪ 本模块 SQL ∪ Worker 运行时加载的 batch-common 片段。
只做静态推导：写入目标额外获得 SELECT（UPDATE/DELETE 的 WHERE、RETURNING 与生成主键都需要读权限）；
行锁子句需要被锁表至少一列的 UPDATE，按“仅锁”列授权；Orchestrator 所有的表只授予实际 SET 的列。
动态拼装表名的 SQL 无法识别，新增此类写法时必须在 EXTRA_GRANTS 显式登记。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = ROOT / "scripts/db/platform-worker-grants.json"
MIGRATION_DIR = ROOT / "db/migration"
SQL_PATH = ROOT / "scripts/db/platform-worker-roles.sql"

WORKERS = ("import", "export", "process", "dispatch", "atomic")
CORE_MODULE = "core"

# Worker 运行时加载的 batch-common 平台库 SQL：业务分片租户路由与有状态后端身份守卫。
SHARED_SOURCES = (
    "batch-common/src/main/resources/mapper/BusinessTenantPlacementMapper.xml",
    "batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java",
)

# 使用 @SchedulerLock 的模块在 BATCH_SHEDLOCK_PROVIDER=jdbc 时读写 ShedLock 表。
SHEDLOCK_TABLE = "batch.shedlock"

# Orchestrator 所有的表：Worker 只获得实际 SET 列的列级 UPDATE（与 job_task 写入守卫白名单一致）。
COLUMN_SCOPED_UPDATE_TABLES = {"batch.job_task"}

# 行锁子句所需的“仅锁”列：选择不承载状态语义的列，避免借锁权改写状态。
LOCK_ONLY_COLUMN = "updated_at"

# 无法静态识别的访问在此显式登记：{worker 或 "*": {table: [privileges]}}。
EXTRA_GRANTS: dict[str, dict[str, list[str]]] = {}

# 业务库 Mapper 由业务数据源执行，不属于平台库授权。
BUSINESS_MAPPER_DIR = "/resources/mapper/business/"

COMMENT = re.compile(r"<!--.*?-->", re.S)
MIGRATION_VERSION = re.compile(r"^V(\d+)__")
CREATE_TABLE = re.compile(
    r"\bcreate\s+(?:unlogged\s+)?table\s+(?:if\s+not\s+exists\s+)?((?:batch|archive)\.\w+)", re.I
)
DROP_TABLE = re.compile(r"\bdrop\s+table\s+(?:if\s+exists\s+)?((?:batch|archive)\.\w+)", re.I)
RENAME_TABLE = re.compile(
    r"\balter\s+table\s+(?:if\s+exists\s+)?((?:batch|archive)\.\w+)\s+rename\s+to\s+(\w+)", re.I
)
XML_TAG = re.compile(r"</?\w+\b[^>]*>")
STATEMENT = re.compile(r"<(insert|update|delete|select)\b([^>]*)>(.*?)</\1>", re.I | re.S)
TABLE_REF = re.compile(
    r"\b(insert\s+into|update|delete\s+from|from|join)\s+((?:batch|archive)\.\w+)", re.I
)
LOCK_CLAUSE = re.compile(r"\bfor\s+(?:no\s+key\s+update|key\s+share|update|share)\b", re.I)
FROM_TABLE = re.compile(r"\bfrom\s+((?:batch|archive)\.\w+)", re.I)
UPDATE_SET = re.compile(r"\bupdate\s+((?:batch|archive)\.\w+)(?:\s+(?:as\s+)?\w+)?\s+set\b", re.I)
SET_END = re.compile(r"\b(from|where|returning)\b", re.I)
ASSIGNED = re.compile(r"^\s*(?:\w+\.)?(\w+)\s*=")
UPSERT = re.compile(r"\bon\s+conflict\b.*?\bdo\s+update\b", re.I | re.S)
OPERATIONS = {"insert": "INSERT", "update": "UPDATE", "delete": "DELETE", "from": "SELECT", "join": "SELECT"}


class Access:
    """单个 Worker 对各表的权限累积：表级权限、列级 UPDATE 与“仅锁”需求。"""

    def __init__(self) -> None:
        self.table_privileges: dict[str, set[str]] = defaultdict(set)
        self.update_columns: dict[str, set[str]] = defaultdict(set)
        self.lock_tables: set[str] = set()
        self.sources: dict[str, set[str]] = defaultdict(set)

    def add(self, table: str, privilege: str, source: str) -> None:
        self.table_privileges[table].add(privilege)
        self.sources[table].add(source)


def sql_fragments(path: Path) -> list[str]:
    """XML 按语句块切分（动态标签只影响拼接，去标签保留内容）；Java 以整个文件作为一个片段。"""
    text = COMMENT.sub(" ", path.read_text(encoding="utf-8"))
    if path.suffix == ".xml":
        return [XML_TAG.sub(" ", match.group(3)) for match in STATEMENT.finditer(text)]
    return [text]


def split_assignments(set_clause: str) -> list[str]:
    columns, depth, start = [], 0, 0
    for index, char in enumerate(set_clause + ","):
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
        elif char == "," and depth == 0:
            assigned = ASSIGNED.match(set_clause[start:index])
            if assigned:
                columns.append(assigned.group(1).lower())
            start = index + 1
    return columns


def set_clause(after_set: str) -> str:
    """SET 子句止于同层 FROM/WHERE/RETURNING，或包住本语句的 CTE 右括号。"""
    depth = 0
    for index, char in enumerate(after_set):
        if char == "(":
            depth += 1
        elif char == ")":
            if depth == 0:
                return after_set[:index]
            depth -= 1
        elif depth == 0 and SET_END.match(after_set, index) and (
            index == 0 or not (after_set[index - 1].isalnum() or after_set[index - 1] == "_")
        ):
            return after_set[:index]
    return after_set


def collect(fragment: str, access: Access, source: str) -> None:
    for operation, table in TABLE_REF.findall(fragment):
        table = table.lower()
        privilege = OPERATIONS[operation.lower().split()[0]]
        access.add(table, privilege, source)
        if privilege != "SELECT":
            access.add(table, "SELECT", source)
        if privilege == "INSERT" and UPSERT.search(fragment):
            access.add(table, "UPDATE", source)
    for match in UPDATE_SET.finditer(fragment):
        table = match.group(1).lower()
        access.update_columns[table].update(split_assignments(set_clause(fragment[match.end():])))
    for lock in LOCK_CLAUSE.finditer(fragment):
        locked = FROM_TABLE.findall(fragment[: lock.start()])
        if locked:
            access.lock_tables.add(locked[-1].lower())


def module_sources(module: str) -> list[Path]:
    base = ROOT / "batch-worker" / module / "src/main"
    return sorted(
        path for pattern in ("resources/**/*.xml", "java/**/*.java") for path in base.glob(pattern)
        if BUSINESS_MAPPER_DIR not in path.as_posix()
    )


def platform_tables() -> set[str]:
    """按版本顺序重放平台 Flyway 迁移的建表、删表与改名，得到当前平台库实际存在的表。

    Worker 内联 SQL 无法静态区分平台库与业务库（两者都用 batch schema），以此过滤掉业务库表。
    """
    tables: set[str] = set()
    migrations = sorted(
        (int(match.group(1)), path)
        for path in MIGRATION_DIR.glob("V*.sql")
        if (match := MIGRATION_VERSION.match(path.name))
    )
    for _, path in migrations:
        text = re.sub(r"--[^\n]*", " ", path.read_text(encoding="utf-8"))
        events = [(m.start(), "create", m.group(1).lower(), None) for m in CREATE_TABLE.finditer(text)]
        events += [(m.start(), "drop", m.group(1).lower(), None) for m in DROP_TABLE.finditer(text)]
        events += [(m.start(), "rename", m.group(1).lower(), m.group(2).lower())
                   for m in RENAME_TABLE.finditer(text)]
        for _, kind, table, renamed in sorted(events):
            if kind == "create":
                tables.add(table)
            elif kind == "drop":
                tables.discard(table)
            else:
                tables.discard(table)
                tables.add(f"{table.split('.')[0]}.{renamed}")
    return tables


def uses_scheduler_lock(module: str) -> bool:
    return any("@SchedulerLock" in path.read_text(encoding="utf-8")
               for path in module_sources(module) if path.suffix == ".java")


def build_manifest() -> dict:
    existing = platform_tables()
    workers = {}
    for worker in WORKERS:
        access = Access()
        sources = module_sources(CORE_MODULE) + module_sources(worker)
        sources += [ROOT / relative for relative in SHARED_SOURCES]
        for path in sources:
            relative = path.relative_to(ROOT).as_posix()
            for fragment in sql_fragments(path):
                collect(fragment, access, relative)
        if uses_scheduler_lock(CORE_MODULE) or uses_scheduler_lock(worker):
            for privilege in ("SELECT", "INSERT", "UPDATE"):
                access.add(SHEDLOCK_TABLE, privilege, "@SchedulerLock")
        for scope in ("*", worker):
            for table, privileges in EXTRA_GRANTS.get(scope, {}).items():
                for privilege in privileges:
                    access.add(table, privilege, "EXTRA_GRANTS")
        workers[worker] = render_worker(access, existing)
    return {
        "description": "由 scripts/db/generate-platform-worker-grants.py 生成，勿手改；见 ARCH-20261103-01。",
        "workers": workers,
    }


def render_worker(access: Access, existing: set[str]) -> dict:
    tables = {}
    excluded = sorted((set(access.table_privileges) | access.lock_tables) - existing)
    for table in sorted(set(access.table_privileges) & existing):
        privileges = set(access.table_privileges[table])
        entry: dict = {}
        if table in COLUMN_SCOPED_UPDATE_TABLES and "UPDATE" in privileges:
            privileges.discard("UPDATE")
            columns = sorted(access.update_columns[table] | {"updated_at"})
            entry["update_columns"] = columns
        if table in access.lock_tables and "UPDATE" not in privileges and "update_columns" not in entry:
            entry["update_columns"] = [LOCK_ONLY_COLUMN]
            entry["lock_only"] = True
        entry["privileges"] = sorted(privileges)
        entry["sources"] = sorted(access.sources[table])
        tables[table] = entry
    for table in sorted((access.lock_tables & existing) - set(tables)):
        tables[table] = {"privileges": ["SELECT"], "update_columns": [LOCK_ONLY_COLUMN],
                         "lock_only": True, "sources": []}
    schemas = sorted({table.split(".")[0] for table in tables})
    # 被排除的是业务库或已退役的平台表，列出来便于审查推导结果。
    return {"schemas": schemas, "tables": dict(sorted(tables.items())), "excluded_non_platform": excluded}


def render_sql(manifest: dict) -> str:
    lines = [
        "-- 用途：为内建 Worker 的平台库角色授予或撤销最小权限（ARCH-20261103-01）；由 generate-platform-worker-grants.py 生成，勿手改。",
        "-- 用法：psql -X -v ON_ERROR_STOP=1 -v action=grant -v import_role=可信角色 [-v export_role=… -v process_role=…",
        "--       -v dispatch_role=… -v atomic_role=…] -f scripts/db/platform-worker-roles.sql",
        "-- 只处理传入的角色；每个角色先撤销 batch/archive 下全部表与序列权限再按清单授予，重复执行收敛到同一状态。",
        "-- 脚本不创建账号、不改 schema，也不调整 Orchestrator/Console/Trigger 角色。action=revoke 时撤销后不再授予。",
        "",
        "\\set ON_ERROR_STOP on",
        "",
        "BEGIN;",
        "",
        "\\if :{?action}",
        "\\else",
        "  DO $$ BEGIN RAISE EXCEPTION '必须通过 action 指定 grant 或 revoke'; END $$;",
        "\\endif",
        "SELECT (:'action' = 'grant') AS is_grant, (:'action' = 'revoke') AS is_revoke \\gset",
        "\\if :is_grant",
        "\\elif :is_revoke",
        "\\else",
        "  DO $$ BEGIN RAISE EXCEPTION 'action 只能是 grant 或 revoke'; END $$;",
        "\\endif",
        "",
        "SELECT (" + " OR ".join(f":{{?{worker}_role}}" for worker in WORKERS) + ") AS has_any_role \\gset",
        "\\if :has_any_role",
        "\\else",
        "  DO $$ BEGIN RAISE EXCEPTION '至少指定一个 *_role 参数'; END $$;",
        "\\endif",
    ]
    for worker, spec in manifest["workers"].items():
        var = f"{worker}_role"
        lines += [
            "",
            f"-- ===== {worker} Worker =====",
            f"\\if :{{?{var}}}",
            f"  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'{var}' AND lower(rolname) <> 'public'",
            f"     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \\gset",
            "  \\if :role_ok",
            "  \\else",
            f"    DO $$ BEGIN RAISE EXCEPTION '{var} 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;",
            "  \\endif",
        ]
        for schema in ("batch", "archive"):
            lines += [
                f"  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA {schema} FROM %I', :'{var}')"
                f" WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = '{schema}') \\gexec",
                f"  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA {schema} FROM %I', :'{var}')"
                f" WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = '{schema}') \\gexec",
                f"  SELECT format('REVOKE USAGE ON SCHEMA {schema} FROM %I', :'{var}')"
                f" WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = '{schema}') \\gexec",
            ]
        lines.append("  \\if :is_grant")
        for schema in spec["schemas"]:
            lines.append(f"    SELECT format('GRANT USAGE ON SCHEMA {schema} TO %I', :'{var}') \\gexec")
        for table, entry in spec["tables"].items():
            if entry["privileges"]:
                privileges = ", ".join(entry["privileges"])
                lines.append(f"    SELECT format('GRANT {privileges} ON {table} TO %I', :'{var}') \\gexec")
            if entry.get("update_columns"):
                columns = ", ".join(entry["update_columns"])
                lines.append(f"    SELECT format('GRANT UPDATE ({columns}) ON {table} TO %I', :'{var}') \\gexec")
            if "INSERT" in entry["privileges"]:
                lines += [
                    "    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'" + var + "')",
                    "      FROM (SELECT DISTINCT pg_get_serial_sequence('" + table + "', attname) AS sequence_name",
                    "              FROM pg_attribute",
                    f"             WHERE attrelid = '{table}'::regclass AND attnum > 0 AND NOT attisdropped) sequences",
                    "     WHERE sequence_name IS NOT NULL \\gexec",
                ]
        lines += ["  \\endif", "\\endif"]
    lines += ["", "COMMIT;", ""]
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--write", action="store_true", help="写入授权清单与 SQL 脚本")
    args = parser.parse_args()
    manifest = build_manifest()
    manifest_text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    sql_text = render_sql(manifest)
    if args.write:
        MANIFEST_PATH.write_text(manifest_text, encoding="utf-8")
        SQL_PATH.write_text(sql_text, encoding="utf-8")
        print(f"已写入 {MANIFEST_PATH.relative_to(ROOT)} 与 {SQL_PATH.relative_to(ROOT)}")
        return 0
    stale = [path.relative_to(ROOT).as_posix() for path, expected in
             ((MANIFEST_PATH, manifest_text), (SQL_PATH, sql_text))
             if not path.exists() or path.read_text(encoding="utf-8") != expected]
    if stale:
        print("❌ 不通过 | code=PLATFORM_WORKER_GRANTS | gate=Worker 平台库授权清单同步 | group=governance"
              " | mode=enforce | exit_code=1 | action=fix_and_retry"
              f" | reason=产物与 Worker SQL 不一致：{', '.join(stale)}；执行 --write 后复核差异", file=sys.stderr)
        return 1
    print("✅ 通过 | code=PLATFORM_WORKER_GRANTS | gate=Worker 平台库授权清单同步 | group=governance"
          " | mode=enforce | exit_code=0 | action=none")
    return 0


if __name__ == "__main__":
    sys.exit(main())
