#!/usr/bin/env python3
"""校验数据库结构治理巡检入口、SQL 资产和文档索引的一致性。"""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "SCHEMA_GOVERNANCE_ASSETS"
GATE_NAME = "数据库结构治理资产"

SCRIPT = ROOT / "scripts/db/inspect-schema-governance.sh"
MAIN_SQL = ROOT / "scripts/db/inspect/schema-governance.sql"
VERSION_SQL = ROOT / "scripts/db/inspect/select-highest-flyway-success-version.sql"
DESIGN_DOC = ROOT / "docs/design/database-schema-governance.md"
DESIGN_INDEX = ROOT / "docs/design/README.md"
SQL_SCENARIOS = ROOT / "scripts/db/sql-script-usage-scenarios.md"

WRITE_SQL_RE = re.compile(
    r"^\s*(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE)\b",
    re.IGNORECASE,
)
PSQL_INLINE_RE = re.compile(r"\bpsql\b.*\s-c\s+")
HEREDOC_RE = re.compile(r"<<\s*'?SQL'?", re.IGNORECASE)


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def read(path: Path, errors: list[str]) -> str:
    if not path.is_file():
        errors.append(f"缺少文件: {rel(path)}")
        return ""
    return path.read_text(encoding="utf-8")


def require_contains(label: str, text: str, needles: list[str], errors: list[str]) -> None:
    missing = [needle for needle in needles if needle not in text]
    if missing:
        errors.append(f"{label} 缺少引用: {', '.join(missing)}")


def main() -> int:
    errors: list[str] = []

    script = read(SCRIPT, errors)
    main_sql = read(MAIN_SQL, errors)
    version_sql = read(VERSION_SQL, errors)
    design_doc = read(DESIGN_DOC, errors)
    design_index = read(DESIGN_INDEX, errors)
    sql_scenarios = read(SQL_SCENARIOS, errors)

    require_contains(
        rel(SCRIPT),
        script,
        [
            "scripts/db/inspect/schema-governance.sql",
            "scripts/db/inspect/select-highest-flyway-success-version.sql",
            "BATCH_SCHEMA_GOVERNANCE_REPORT",
            "BATCH_SCHEMA_GOVERNANCE_SQL",
            "psql -X",
            "-f \"$SQL_FILE\"",
            "-f \"$MIGRATION_VERSION_SQL\"",
        ],
        errors,
    )
    if "read-only" not in script.lower() and "只读" not in script:
        errors.append(f"{rel(SCRIPT)} 缺少只读说明")

    for path, text in ((MAIN_SQL, main_sql), (VERSION_SQL, version_sql)):
        if not text.strip():
            errors.append(f"{rel(path)} 为空")
        for line_number, line in enumerate(text.splitlines(), 1):
            if line.lstrip().startswith(("--", "\\echo")):
                continue
            if WRITE_SQL_RE.search(line):
                errors.append(f"{rel(path)}:{line_number} 应保持只读,不得包含写入或 DDL 语句")

    for line_number, line in enumerate(script.splitlines(), 1):
        stripped = line.lstrip()
        if stripped.startswith("#"):
            continue
        if PSQL_INLINE_RE.search(line) or HEREDOC_RE.search(line):
            errors.append(f"{rel(SCRIPT)}:{line_number} 疑似内联 SQL,请放入 scripts/db/inspect/*.sql")

    require_contains(
        rel(DESIGN_DOC),
        design_doc,
        [
            "scripts/db/inspect-schema-governance.sh",
            "BATCH_SCHEMA_GOVERNANCE_REPORT",
            "idx_scan=0",
            "默认分区",
            "archive",
        ],
        errors,
    )
    require_contains(
        rel(DESIGN_INDEX),
        design_index,
        ["database-schema-governance.md", "只读检查入口"],
        errors,
    )
    require_contains(
        rel(SQL_SCENARIOS),
        sql_scenarios,
        [
            "scripts/db/inspect/",
            "scripts/db/inspect-schema-governance.sh",
            "Shell 脚本中不要新增内联 SQL",
        ],
        errors,
    )

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
