#!/usr/bin/env python3
# 用途：从内建 Worker 与 Console/Trigger/Orchestrator 的 Mapper XML 与内联 SQL 推导平台库最小授权，生成授权清单与 psql 授权脚本（ARCH-20261103-01）。
# 用法：python3 scripts/db/generate-platform-worker-grants.py [--write]；默认只校验已提交产物与源码一致，不一致时退出 1。
"""平台库授权清单生成器（Worker 与控制面）。

每个 Worker 的权限 = worker-core 公共 SQL ∪ 本模块 SQL ∪ Worker 运行时加载的 batch-common 片段；
控制面模块的权限 = 本模块 SQL ∪ 同一组 batch-common 片段。
入站归属声明属于跨模块读取，仅对实际使用该公共仓储的 Import 与 Orchestrator 加入该源码。
只做静态推导：写入目标额外获得 SELECT（UPDATE/DELETE 的 WHERE、RETURNING 与生成主键都需要读权限）；
行锁子句需要被锁表至少一列的 UPDATE，按“仅锁”列授权；Orchestrator 所有的表只授予实际 SET 的列。
触发器函数以调用者身份执行：模块写入的表上挂有非 SECURITY DEFINER 触发器时，函数体访问的表一并授予。
控制面另按调用登记已撤销 PUBLIC 执行权的平台函数的 EXECUTE。
动态拼装表名的 SQL 与第三方库自带的 SQL（如 Quartz）无法识别，必须在 EXTRA_GRANTS 显式登记。
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
CONTROL_PLANE_MANIFEST_PATH = ROOT / "scripts/db/platform-control-plane-grants.json"
CONTROL_PLANE_SQL_PATH = ROOT / "scripts/db/platform-control-plane-roles.sql"

WORKERS = ("import", "export", "process", "dispatch", "atomic")
CORE_MODULE = "core"

# 控制面模块与源码根目录。Console 不执行 Flyway；Trigger/Orchestrator 的迁移改由独立的 owner 账号执行。
CONTROL_PLANE = {
    "console": "batch-console-api",
    "trigger": "batch-trigger",
    "orchestrator": "batch-orchestrator",
}

# Worker 运行时加载的 batch-common 平台库 SQL：业务分片租户路由与有状态后端身份守卫。
SHARED_SOURCES = (
    "batch-common/src/main/resources/mapper/BusinessTenantPlacementMapper.xml",
    "batch-common/src/main/java/io/github/pinpols/batch/common/stateful/StatefulBackendGuard.java",
)

# 入站归属配置仅由 Import scanner 与 Orchestrator 对账读取，不能授予所有 Worker/控制面。
INGRESS_OWNERSHIP_SOURCE = "batch-common/src/main/resources/mapper/ImportIngressOwnershipMapper.xml"

# 使用 @SchedulerLock 的模块在 BATCH_SHEDLOCK_PROVIDER=jdbc 时读写 ShedLock 表。
SHEDLOCK_TABLE = "batch.shedlock"

# Orchestrator 所有的表：Worker 只获得实际 SET 列的列级 UPDATE（与 job_task 写入守卫白名单一致）。
COLUMN_SCOPED_UPDATE_TABLES = {"batch.job_task"}

# 行锁子句所需的“仅锁”列：选择不承载状态语义的列，避免借锁权改写状态；表上没有该列时退回主键首列。
LOCK_ONLY_COLUMN = "updated_at"

# 无法静态识别的访问在此显式登记：{worker 或 "*": {table: [privileges]}}。
EXTRA_GRANTS: dict[str, dict[str, list[str]]] = {}

# 控制面无法静态识别的访问：{module: {schema: [privileges]}} 授予该 schema 下全部平台表。
# Trigger 使用 Quartz JDBC JobStore（tablePrefix quartz.QRTZ_），其 SQL 由 Quartz 库生成。
CONTROL_PLANE_SCHEMA_GRANTS: dict[str, dict[str, list[str]]] = {
    "trigger": {"quartz": ["DELETE", "INSERT", "SELECT", "UPDATE"]},
}

# Orchestrator 启动期 ArchiveSchemaDriftCheck 经 information_schema 比对冷热表列；该视图只列出当前角色有权限的列，
# 缺权限会被当作漂移而阻断启动。清单内每对冷热表授予只读权限，表名直接取自 ARCHIVED_TABLES，保持同步。
ARCHIVE_DRIFT_CHECK_SOURCE = (
    "batch-orchestrator/src/main/java/io/github/pinpols/batch/orchestrator/infrastructure/archive/"
    "ArchiveSchemaDriftCheck.java"
)
ARCHIVED_TABLES_LIST = re.compile(r"ARCHIVED_TABLES\s*=\s*List\.of\((.*?)\);", re.S)

# 业务库 Mapper 由业务数据源执行，不属于平台库授权。
BUSINESS_MAPPER_DIR = "/resources/mapper/business/"

COMMENT = re.compile(r"<!--.*?-->", re.S)
MIGRATION_VERSION = re.compile(r"^V(\d+)__")
CREATE_TABLE = re.compile(
    r"\bcreate\s+(?:unlogged\s+)?table\s+(?:if\s+not\s+exists\s+)?((?:batch|archive|quartz)\.\w+)", re.I
)
DROP_TABLE = re.compile(r"\bdrop\s+table\s+(?:if\s+exists\s+)?((?:batch|archive|quartz)\.\w+)", re.I)
RENAME_TABLE = re.compile(
    r"\balter\s+table\s+(?:if\s+exists\s+)?((?:batch|archive|quartz)\.\w+)\s+rename\s+to\s+(\w+)", re.I
)
FUNCTION_DEF = re.compile(
    r"\bcreate\s+(?:or\s+replace\s+)?(?:function|procedure)\s+((?:batch|archive)\.\w+)\s*\(", re.I
)
REVOKED_FROM_PUBLIC = re.compile(
    r"\brevoke\s+(?:all|execute)\s+on\s+(?:function|procedure)\s+((?:batch|archive)\.\w+)\s*\([^;]*?\bfrom\s+public\b",
    re.I,
)
FUNCTION_START = re.compile(
    r"\bcreate\s+(?:or\s+replace\s+)?function\s+((?:batch|archive)\.\w+)\s*\(", re.I
)
DOLLAR_TAG = re.compile(r"\$(\w*)\$")
CREATE_TRIGGER = re.compile(
    r"\bcreate\s+(?:or\s+replace\s+)?(?:constraint\s+)?trigger\s+(\w+)\s+(?:before|after|instead\s+of)\s+"
    r"(.*?)\s+on\s+((?:batch|archive)\.\w+)\b.*?\bexecute\s+(?:function|procedure)\s+((?:batch|archive)\.\w+)\s*\(",
    re.I | re.S,
)
DROP_TRIGGER = re.compile(
    r"\bdrop\s+trigger\s+(?:if\s+exists\s+)?(\w+)\s+on\s+((?:batch|archive)\.\w+)", re.I
)
TRIGGER_EVENT = re.compile(r"\b(insert|update|delete)\b", re.I)
SQL_FRAGMENT = re.compile(r"<sql\s+id=\"([^\"]+)\"[^>]*>(.*?)</sql>", re.I | re.S)
INCLUDE = re.compile(r"<include\s+refid=\"([^\"]+)\"\s*(?:/>|>\s*</include>)", re.I)
TABLE_NAME = re.compile(r"(?:batch|archive|quartz)\.\w+", re.I)
XML_TAG = re.compile(r"</?\w+\b[^>]*>")
STATEMENT = re.compile(r"<(insert|update|delete|select|sql)\b([^>]*)>(.*?)</\1>", re.I | re.S)
TABLE_REF = re.compile(
    r"\b(insert\s+into|update|delete\s+from|from|join)\s+((?:batch|archive|quartz)\.\w+)", re.I
)
LOCK_CLAUSE = re.compile(r"\bfor\s+(?:no\s+key\s+update|key\s+share|update|share)\b", re.I)
ALIASED_TABLE = re.compile(
    r"\b(?:from|join|update)\s+((?:batch|archive|quartz)\.\w+)(?:\s+(?:as\s+)?(\w+))?", re.I
)
LOCK_OF = re.compile(r"\s+of\s+(\w+(?:\s*,\s*\w+)*)", re.I)
SQL_KEYWORDS = {"where", "join", "left", "right", "inner", "outer", "cross", "on", "set", "for",
                "group", "order", "limit", "union", "using", "returning", "having", "window",
                "natural", "full", "lateral", "as", "and", "or", "offset", "fetch", "values"}
FROM_TABLE = re.compile(r"\bfrom\s+((?:batch|archive|quartz)\.\w+)", re.I)
UPDATE_SET = re.compile(r"\bupdate\s+((?:batch|archive|quartz)\.\w+)(?:\s+(?:as\s+)?\w+)?\s+set\b", re.I)
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
        self.functions: set[str] = set()
        self.sources: dict[str, set[str]] = defaultdict(set)

    def add(self, table: str, privilege: str, source: str) -> None:
        self.table_privileges[table].add(privilege)
        self.sources[table].add(source)


def sql_fragments(path: Path) -> list[str]:
    """XML 按语句块与 <sql> 复用片段切分（动态标签只影响拼接，去标签保留内容）；Java 以整个文件作为一个片段。

    <include refid> 按 MyBatis 语义内联同文件的 <sql> 片段；片段只在 <choose> 分支间选择表名时，语句按分支展开为多个
    变体，保证每个候选表都按该位置的操作授权。
    """
    text = COMMENT.sub(" ", path.read_text(encoding="utf-8"))
    if path.suffix != ".xml":
        return [text]
    fragments = {match.group(1): match.group(2) for match in SQL_FRAGMENT.finditer(text)}
    statements = []
    for match in STATEMENT.finditer(text):
        for variant in expand_includes(match.group(3), fragments):
            statements.append(XML_TAG.sub(" ", variant))
    return statements


def table_choices(content: str) -> list[str] | None:
    """片段去标签后只剩若干 schema.table 时返回候选表，否则返回 None。"""
    tokens = XML_TAG.sub(" ", content).split()
    if tokens and all(TABLE_NAME.fullmatch(token) for token in tokens):
        return tokens
    return None


def expand_includes(body: str, fragments: dict[str, str], depth: int = 0) -> list[str]:
    include = INCLUDE.search(body)
    if not include or depth > 8:
        return [body]
    refid = include.group(1)
    content = fragments.get(refid) or fragments.get(refid.rsplit(".", 1)[-1], "")
    choices = table_choices(content)
    replacements = choices if choices else [content]
    variants = []
    for replacement in replacements:
        expanded = body[:include.start()] + " " + replacement + " " + body[include.end():]
        variants.extend(expand_includes(expanded, fragments, depth + 1))
    return variants


def split_assignments(set_clause: str) -> list[str]:
    columns, depth, start = [], 0, 0
    # MyBatis 参数 #{value, jdbcType=…} 内的逗号不分隔赋值，花括号与圆括号同样计入嵌套深度。
    for index, char in enumerate(set_clause + ","):
        if char in "({":
            depth += 1
        elif char in ")}":
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
    aliases = {}
    for table, alias in ALIASED_TABLE.findall(fragment):
        aliases[table.lower()] = table.lower()
        if alias and alias.lower() not in SQL_KEYWORDS:
            aliases[alias.lower()] = table.lower()
    for lock in LOCK_CLAUSE.finditer(fragment):
        # FOR UPDATE OF a, b 只锁所列别名对应的表；未列出时按锁子句前最后一个 FROM 表推断。
        targets = LOCK_OF.match(fragment, lock.end())
        if targets:
            for name in re.split(r"\s*,\s*", targets.group(1).strip()):
                if name.lower() in aliases:
                    access.lock_tables.add(aliases[name.lower()])
            continue
        locked = FROM_TABLE.findall(fragment[: lock.start()])
        if locked:
            access.lock_tables.add(locked[-1].lower())


def module_sources(module: str) -> list[Path]:
    return sources_under(ROOT / "batch-worker" / module / "src/main")


def sources_under(base: Path) -> list[Path]:
    return sorted(
        path for pattern in ("resources/**/*.xml", "java/**/*.java") for path in base.glob(pattern)
        if BUSINESS_MAPPER_DIR not in path.as_posix()
    )


def revoked_public_functions() -> set[str]:
    """平台迁移中撤销了 PUBLIC 执行权的函数：调用方必须显式获得 EXECUTE。"""
    revoked: set[str] = set()
    for path in MIGRATION_DIR.glob("V*.sql"):
        text = re.sub(r"--[^\n]*", " ", path.read_text(encoding="utf-8"))
        revoked.update(match.group(1).lower() for match in REVOKED_FROM_PUBLIC.finditer(text))
    return revoked


def collect_function_calls(fragment: str, access: Access, functions: set[str], source: str) -> None:
    lowered = fragment.lower()
    for function in functions:
        if re.search(r"\b" + re.escape(function) + r"\s*\(", lowered):
            access.functions.add(function)
            access.sources[function].add(source)


def ordered_migrations() -> list[tuple[int, Path]]:
    return sorted(
        (int(match.group(1)), path)
        for path in MIGRATION_DIR.glob("V*.sql")
        if (match := MIGRATION_VERSION.match(path.name))
    )


def table_triggers() -> list[tuple[str, set[str], str, bool]]:
    """按迁移顺序重放触发器与函数定义，返回当前生效的 (表, 触发事件, 函数体, 是否 SECURITY DEFINER)。"""
    functions: dict[str, tuple[str, bool]] = {}
    triggers: dict[tuple[str, str], tuple[set[str], str]] = {}
    for _, path in ordered_migrations():
        text = re.sub(r"--[^\n]*", " ", path.read_text(encoding="utf-8"))
        events = []
        for match in FUNCTION_START.finditer(text):
            tag = DOLLAR_TAG.search(text, match.end())
            if not tag:
                continue
            body_end = text.find(tag.group(0), tag.end())
            statement_end = text.find(";", body_end)
            header = text[match.end():tag.start()] + text[body_end:statement_end]
            body = text[tag.end():body_end]
            definer = re.search(r"\bsecurity\s+definer\b", header, re.I) is not None
            events.append((match.start(), "function", match.group(1).lower(), (body, definer)))
        for match in CREATE_TRIGGER.finditer(text):
            fired = {event.upper() for event in TRIGGER_EVENT.findall(match.group(2))}
            key = (match.group(3).lower(), match.group(1).lower())
            events.append((match.start(), "trigger", key, (fired, match.group(4).lower())))
        for match in DROP_TRIGGER.finditer(text):
            events.append((match.start(), "drop", (match.group(2).lower(), match.group(1).lower()), None))
        for _, kind, key, value in sorted(events, key=lambda event: event[0]):
            if kind == "function":
                functions[key] = value
            elif kind == "trigger":
                triggers[key] = value
            else:
                triggers.pop(key, None)
    resolved = []
    for (table, _), (fired, function) in sorted(triggers.items()):
        if function in functions:
            body, definer = functions[function]
            resolved.append((table, fired, body, definer))
    return resolved


def expand_trigger_dependencies(access: Access, triggers: list, revoked: set[str]) -> None:
    """模块对表的写入会触发调用者身份的触发器函数，函数体访问的表与函数需同样授予；按传递闭包展开。"""
    applied: set[int] = set()
    changed = True
    while changed:
        changed = False
        for index, (table, fired, body, definer) in enumerate(triggers):
            if index in applied or definer:
                continue
            privileges = access.table_privileges.get(table, set())
            writes = set(privileges) | ({"UPDATE"} if access.update_columns.get(table) else set())
            if fired & writes:
                source = f"trigger on {table}"
                collect(body, access, source)
                collect_function_calls(body, access, revoked, source)
                applied.add(index)
                changed = True


def platform_tables() -> set[str]:
    """按版本顺序重放平台 Flyway 迁移的建表、删表与改名，得到当前平台库实际存在的表。

    Worker 内联 SQL 无法静态区分平台库与业务库（两者都用 batch schema），以此过滤掉业务库表。
    """
    tables: set[str] = set()
    for _, path in ordered_migrations():
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
    return any_scheduler_lock(module_sources(module))


def any_scheduler_lock(paths: list[Path]) -> bool:
    return any("@SchedulerLock" in path.read_text(encoding="utf-8")
               for path in paths if path.suffix == ".java")


def build_manifest() -> dict:
    existing = platform_tables()
    triggers = table_triggers()
    revoked = revoked_public_functions()
    workers = {}
    for worker in WORKERS:
        access = Access()
        sources = module_sources(CORE_MODULE) + module_sources(worker)
        sources += [ROOT / relative for relative in SHARED_SOURCES]
        if worker == "import":
            sources.append(ROOT / INGRESS_OWNERSHIP_SOURCE)
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
        expand_trigger_dependencies(access, triggers, revoked)
        workers[worker] = render_worker(access, existing)
    return {
        "description": "由 scripts/db/generate-platform-worker-grants.py 生成，勿手改；见 ARCH-20261103-01。",
        "workers": workers,
    }


def archive_drift_check_tables() -> list[str]:
    source = (ROOT / ARCHIVE_DRIFT_CHECK_SOURCE).read_text(encoding="utf-8")
    listed = ARCHIVED_TABLES_LIST.search(source)
    if not listed:
        raise SystemExit(f"未在 {ARCHIVE_DRIFT_CHECK_SOURCE} 找到 ARCHIVED_TABLES")
    names = re.findall(r'"(\w+)"', listed.group(1))
    return [f"batch.{name}" for name in names] + [f"archive.{name}_archive" for name in names]


def build_control_plane_manifest() -> dict:
    existing = platform_tables()
    triggers = table_triggers()
    revoked = revoked_public_functions()
    modules = {}
    for module, directory in CONTROL_PLANE.items():
        access = Access()
        module_paths = sources_under(ROOT / directory / "src/main")
        shared_paths = [ROOT / relative for relative in SHARED_SOURCES]
        if module == "orchestrator":
            shared_paths.append(ROOT / INGRESS_OWNERSHIP_SOURCE)
        for path in module_paths + shared_paths:
            relative = path.relative_to(ROOT).as_posix()
            for fragment in sql_fragments(path):
                collect(fragment, access, relative)
                collect_function_calls(fragment, access, revoked, relative)
        if any_scheduler_lock(module_paths):
            for privilege in ("SELECT", "INSERT", "UPDATE"):
                access.add(SHEDLOCK_TABLE, privilege, "@SchedulerLock")
        for schema, privileges in CONTROL_PLANE_SCHEMA_GRANTS.get(module, {}).items():
            for table in sorted(table for table in existing if table.startswith(schema + ".")):
                for privilege in privileges:
                    access.add(table, privilege, "CONTROL_PLANE_SCHEMA_GRANTS")
        if module == "orchestrator":
            for table in archive_drift_check_tables():
                if table in existing:
                    access.add(table, "SELECT", ARCHIVE_DRIFT_CHECK_SOURCE)
        expand_trigger_dependencies(access, triggers, revoked)
        rendered = render_worker(access, existing, column_scoped=set())
        rendered["functions"] = sorted(access.functions)
        modules[module] = rendered
    return {
        "description": "由 scripts/db/generate-platform-worker-grants.py 生成，勿手改；见 ARCH-20261103-01。",
        "modules": modules,
    }


def render_worker(access: Access, existing: set[str],
                  column_scoped: set[str] = COLUMN_SCOPED_UPDATE_TABLES) -> dict:
    tables = {}
    excluded = sorted((set(access.table_privileges) | access.lock_tables) - existing)
    for table in sorted(set(access.table_privileges) & existing):
        privileges = set(access.table_privileges[table])
        entry: dict = {}
        if table in column_scoped and "UPDATE" in privileges:
            privileges.discard("UPDATE")
            columns = sorted(access.update_columns[table] | {"updated_at"})
            entry["update_columns"] = columns
        if table in access.lock_tables and "UPDATE" not in privileges and "update_columns" not in entry:
            entry["lock_only"] = True
        entry["privileges"] = sorted(privileges)
        entry["sources"] = sorted(access.sources[table])
        tables[table] = entry
    for table in sorted((access.lock_tables & existing) - set(tables)):
        tables[table] = {"privileges": ["SELECT"], "lock_only": True, "sources": []}
    schemas = sorted({table.split(".")[0] for table in tables})
    # 被排除的是业务库或已退役的平台表，列出来便于审查推导结果。
    return {"schemas": schemas, "tables": dict(sorted(tables.items())), "excluded_non_platform": excluded}


WORKER_SQL_HEADER = (
    "-- 用途：为内建 Worker 的平台库角色授予或撤销最小权限（ARCH-20261103-01）；由 generate-platform-worker-grants.py 生成，勿手改。",
    "-- 用法：psql -X -v ON_ERROR_STOP=1 -v action=grant -v import_role=可信角色 [-v export_role=… -v process_role=…",
    "--       -v dispatch_role=… -v atomic_role=…] -f scripts/db/platform-worker-roles.sql",
    "-- 只处理传入的角色；每个角色先撤销 batch/archive 下全部表与序列权限再按清单授予，重复执行收敛到同一状态。",
    "-- 脚本不创建账号、不改 schema，也不调整 Orchestrator/Console/Trigger 角色。action=revoke 时撤销后不再授予。",
    "-- Import 角色还须执行 import-ingress-scan-role.sql 配置入口扫描协调状态的跨租户 RLS 策略。",
    "-- 使用统一入口归属的 Import 角色须执行 import-ingress-ownership-role.sql 配置只读 RLS 策略。",
)

CONTROL_PLANE_SQL_HEADER = (
    "-- 用途：为 Console/Trigger/Orchestrator 的平台库运行时角色授予或撤销最小权限（ARCH-20261103-01）；由 generate-platform-worker-grants.py 生成，勿手改。",
    "-- 用法：psql -X -v ON_ERROR_STOP=1 -v action=grant -v console_role=可信角色 [-v trigger_role=… -v orchestrator_role=…]",
    "--       -f scripts/db/platform-control-plane-roles.sql",
    "-- 只处理传入的角色；每个角色先撤销 batch/archive/quartz 下全部表、序列与函数权限再按清单授予，重复执行收敛到同一状态。",
    "-- 运行时角色不执行 Flyway：迁移由 BATCH_PLATFORM_FLYWAY_USERNAME 指定的 owner 账号执行，每次迁移后重新执行本脚本。",
    "-- Console 角色另需执行 config-cache-invalidation-role.sql 获得跨租户的缓存失效队列策略。action=revoke 时撤销后不再授予。",
    "-- 使用统一入口归属的 Orchestrator 角色须执行 import-ingress-ownership-role.sql 配置只读 RLS 策略。",
)


def render_sql(manifest: dict) -> str:
    return render_roles_sql(WORKER_SQL_HEADER, manifest["workers"], "{name} Worker", ("batch", "archive"))


def render_control_plane_sql(manifest: dict) -> str:
    return render_roles_sql(
        CONTROL_PLANE_SQL_HEADER, manifest["modules"], "{name}", ("batch", "archive", "quartz"))


def render_roles_sql(header: tuple[str, ...], roles: dict, label: str, revoke_schemas: tuple[str, ...]) -> str:
    lines = [
        *header,
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
        "SELECT (" + " OR ".join(f":{{?{name}_role}}" for name in roles) + ") AS has_any_role \\gset",
        "\\if :has_any_role",
        "\\else",
        "  DO $$ BEGIN RAISE EXCEPTION '至少指定一个 *_role 参数'; END $$;",
        "\\endif",
    ]
    for name, spec in roles.items():
        var = f"{name}_role"
        lines += [
            "",
            f"-- ===== {label.format(name=name)} =====",
            f"\\if :{{?{var}}}",
            f"  SELECT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'{var}' AND lower(rolname) <> 'public'",
            f"     AND NOT rolsuper AND NOT rolbypassrls) AS role_ok \\gset",
            "  \\if :role_ok",
            "  \\else",
            f"    DO $$ BEGIN RAISE EXCEPTION '{var} 不存在、为 PUBLIC，或具备 SUPERUSER/BYPASSRLS'; END $$;",
            "  \\endif",
        ]
        for schema in revoke_schemas:
            lines += [
                f"  SELECT format('REVOKE ALL ON ALL TABLES IN SCHEMA {schema} FROM %I', :'{var}')"
                f" WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = '{schema}') \\gexec",
                f"  SELECT format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA {schema} FROM %I', :'{var}')"
                f" WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = '{schema}') \\gexec",
            ]
            if "functions" in spec:
                lines.append(
                    f"  SELECT format('REVOKE ALL ON ALL FUNCTIONS IN SCHEMA {schema} FROM %I', :'{var}')"
                    f" WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = '{schema}') \\gexec")
            lines.append(
                f"  SELECT format('REVOKE USAGE ON SCHEMA {schema} FROM %I', :'{var}')"
                f" WHERE EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = '{schema}') \\gexec")
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
            if entry.get("lock_only"):
                # 列不存在时 %I 遇到 NULL 报错，阻止静默漏授权。
                lines += [
                    "    SELECT format('GRANT UPDATE (%I) ON " + table + " TO %I', COALESCE(",
                    "        (SELECT attname FROM pg_attribute WHERE attrelid = '" + table + "'::regclass",
                    f"            AND attname = '{LOCK_ONLY_COLUMN}' AND NOT attisdropped),",
                    "        (SELECT a.attname FROM pg_index i JOIN pg_attribute a",
                    "            ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]",
                    "          WHERE i.indrelid = '" + table + "'::regclass AND i.indisprimary)), :'" + var + "') \\gexec",
                ]
            if "INSERT" in entry["privileges"]:
                lines += [
                    "    SELECT format('GRANT USAGE ON SEQUENCE %s TO %I', sequence_name, :'" + var + "')",
                    "      FROM (SELECT DISTINCT pg_get_serial_sequence('" + table + "', attname) AS sequence_name",
                    "              FROM pg_attribute",
                    f"             WHERE attrelid = '{table}'::regclass AND attnum > 0 AND NOT attisdropped) sequences",
                    "     WHERE sequence_name IS NOT NULL \\gexec",
                ]
        for function in spec.get("functions", []):
            schema, function_name = function.split(".")
            lines += [
                "    SELECT format('GRANT EXECUTE ON FUNCTION %s TO %I', p.oid::regprocedure, :'" + var + "')",
                "      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace",
                f"     WHERE n.nspname = '{schema}' AND p.proname = '{function_name}' \\gexec",
            ]
        lines += ["  \\endif", "\\endif"]
    lines += ["", "COMMIT;", ""]
    return "\n".join(lines)


GATE = "code=PLATFORM_WORKER_GRANTS | gate=平台库授权清单同步 | group=governance | mode=enforce"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--write", action="store_true", help="写入授权清单与 SQL 脚本")
    args = parser.parse_args()
    manifest = build_manifest()
    control_plane = build_control_plane_manifest()
    outputs = (
        (MANIFEST_PATH, json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"),
        (SQL_PATH, render_sql(manifest)),
        (CONTROL_PLANE_MANIFEST_PATH, json.dumps(control_plane, ensure_ascii=False, indent=2) + "\n"),
        (CONTROL_PLANE_SQL_PATH, render_control_plane_sql(control_plane)),
    )
    if args.write:
        for path, text in outputs:
            path.write_text(text, encoding="utf-8")
        print("已写入 " + "、".join(path.relative_to(ROOT).as_posix() for path, _ in outputs))
        return 0
    stale = [path.relative_to(ROOT).as_posix() for path, expected in outputs
             if not path.exists() or path.read_text(encoding="utf-8") != expected]
    if stale:
        print(f"❌ 不通过 | {GATE} | exit_code=1 | action=fix_and_retry"
              f" | reason=产物与模块 SQL 不一致：{', '.join(stale)}；执行 --write 后复核差异", file=sys.stderr)
        return 1
    print(f"✅ 通过 | {GATE} | exit_code=0 | action=none")
    return 0


if __name__ == "__main__":
    sys.exit(main())
