#!/usr/bin/env python3
# 用途：新增数据库表文档登记。
# 用法：在仓库根目录执行 python3 scripts/ci/check-new-table-doc-coverage.py；参数与前置环境见下方说明及所属 README。
"""新增平台表必须登记到数据库结构指南。"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCHEMA_GUIDE = ROOT / "docs/design/database-schema-guide.md"
GATE_CODE = "NEW_TABLE_DOC_COVERAGE"
GATE_NAME = "新增数据库表文档登记"
MIGRATION_RE = re.compile(r"^db/migration/V\d+.*\.sql$")
CREATE_TABLE_RE = re.compile(
    r"^[ \t]*CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?"
    r"((?:batch|archive)\.[a-z][a-z0-9_]*)\b(.*?)(?=;|\Z)",
    re.IGNORECASE | re.MULTILINE | re.DOTALL,
)


def run_git(*args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=ROOT, check=True, capture_output=True, text=True
    )
    return result.stdout


def changed_migrations(base: str) -> set[str]:
    paths = set(
        run_git(
            "diff",
            "--name-only",
            "--diff-filter=AM",
            f"{base}...HEAD",
            "--",
            "db/migration/V*.sql",
        ).splitlines()
    )
    paths.update(
        run_git(
            "diff", "--name-only", "--diff-filter=AM", "--", "db/migration/V*.sql"
        ).splitlines()
    )
    paths.update(
        run_git(
            "diff", "--cached", "--name-only", "--diff-filter=AM", "--", "db/migration/V*.sql"
        ).splitlines()
    )
    paths.update(
        run_git(
            "ls-files", "--others", "--exclude-standard", "--", "db/migration/V*.sql"
        ).splitlines()
    )
    return {path for path in paths if MIGRATION_RE.match(path)}


def added_sql(path: str, base: str) -> str:
    try:
        run_git("cat-file", "-e", f"{base}:{path}")
    except subprocess.CalledProcessError:
        return (ROOT / path).read_text(encoding="utf-8")

    diff = run_git(
        "diff", "--no-ext-diff", "--unified=0", f"{base}...HEAD", "--", path
    )
    lines = [line[1:] for line in diff.splitlines() if line.startswith("+") and not line.startswith("+++")]
    unstaged = run_git("diff", "--no-ext-diff", "--unified=0", "--", path)
    staged = run_git("diff", "--cached", "--no-ext-diff", "--unified=0", "--", path)
    for current in (unstaged, staged):
        lines.extend(
            line[1:]
            for line in current.splitlines()
            if line.startswith("+") and not line.startswith("+++")
        )
    return "\n".join(lines)


def created_tables(sql: str) -> set[str]:
    return {
        match.group(1).lower()
        for match in CREATE_TABLE_RE.finditer(sql)
        if not re.match(r"\s+PARTITION\s+OF\b", match.group(2), re.IGNORECASE)
    }


def find_undocumented(tables: set[str], guide: str) -> list[str]:
    catalog = schema_catalog_tables(guide)
    return sorted(table for table in tables if table.split(".", 1)[1] not in catalog)


def schema_catalog_tables(guide: str) -> set[str]:
    in_catalog = False
    names: set[str] = set()
    for line in guide.splitlines():
        if line.startswith("### 3.1 "):
            in_catalog = True
        elif line.startswith("### 3.6 "):
            break
        if in_catalog and line.startswith("|"):
            first_cell = line.split("|", 2)[1]
            names.update(re.findall(r"`([a-z][a-z0-9_]*)`", first_cell))
    return names


def removed_catalog_tables(previous: str, current: str) -> list[str]:
    return sorted(schema_catalog_tables(previous) - schema_catalog_tables(current))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="origin/main", help="Git base ref for incremental migration detection")
    args = parser.parse_args()

    try:
        migrations = changed_migrations(args.base)
        tables = (
            set().union(*(created_tables(added_sql(path, args.base)) for path in migrations))
            if migrations
            else set()
        )
        guide = SCHEMA_GUIDE.read_text(encoding="utf-8")
        previous_guide = run_git("show", f"{args.base}:docs/design/database-schema-guide.md")
    except (OSError, subprocess.CalledProcessError) as exc:
        print(
            f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | group=database "
            "| mode=enforce | exit_code=2 | action=fix_and_retry",
            file=sys.stderr,
        )
        print(f"无法完成检查: {exc}", file=sys.stderr)
        return 2

    missing = find_undocumented(tables, guide)
    removed = removed_catalog_tables(previous_guide, guide)
    if missing or removed:
        print(
            f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | group=database "
            "| mode=enforce | exit_code=1 | action=fix_and_retry",
            file=sys.stderr,
        )
        if missing:
            print("新建平台表未登记到 docs/design/database-schema-guide.md:")
            for table in missing:
                print(f"  - {table}")
            print("请在同一变更中补充表职责/边界，并链接对应迁移。")
        if removed:
            print("数据库结构目录中已有的表条目被删除:")
            for table in removed:
                print(f"  - {table}")
            print("如表已退役，仍保留历史目录条目并标注退役迁移。")
        return 1

    if tables:
        print(
            f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} | group=database "
            "| mode=enforce | exit_code=0 | action=none"
        )
        print(f"已核对新增表登记数量: {len(tables)}")
    else:
        print(
            f"⏭️ 跳过 | code={GATE_CODE} | gate={GATE_NAME} | group=database "
            "| mode=conditional | exit_code=0 | action=none "
            "| reason=未发现本次新增 batch/archive 表"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
