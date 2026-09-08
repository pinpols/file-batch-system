#!/usr/bin/env python3
"""阻止 Shell 脚本新增内联 SQL，并约束历史债务只能减少。"""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BASELINE_FILE = ROOT / "scripts/ci/sql-inline-baseline.tsv"
SCAN_ROOTS = (ROOT / "scripts", ROOT / "load-tests")
EXCLUDED_FILES = {Path("scripts/ci/check-sql-config-boundaries.sh")}

PSQL_COMMAND = re.compile(r"\bpsql\b[^#\n]*(?:^|\s)-[A-Za-z]*c(?:\s|$)", re.IGNORECASE)
SQL_HEREDOC = re.compile(r"<<\s*['\"]?SQL['\"]?\s*$", re.IGNORECASE)
SQL_STATEMENT = re.compile(
    r"(?:^|[\"'`])\s*(?:"
    r"select\s+|insert\s+into\s+|update\s+[A-Za-z_$\"'{]|delete\s+from\s+|"
    r"truncate\s+(?:table\s+)?|create\s+(?:temp(?:orary)?\s+)?table\s+|"
    r"alter\s+table\s+|drop\s+table\s+|do\s+\$[A-Za-z_]*\$|"
    r"with\s+[A-Za-z_][A-Za-z0-9_]*\s+as\s*\()",
    re.IGNORECASE,
)
SQL_CONSTRUCTOR = re.compile(r"\bjsonb_build_(?:object|array)\s*\(", re.IGNORECASE)
IGNORED_COMMAND = re.compile(r"^\s*(?:#|echo\b|printf\b|log\b|curl\b)")
IGNORE_MARKER = "sql-boundary: ignore"


def shell_files() -> list[Path]:
    files: list[Path] = []
    for scan_root in SCAN_ROOTS:
        files.extend(scan_root.rglob("*.sh"))
    return sorted(path for path in files if path.relative_to(ROOT) not in EXCLUDED_FILES)


def matched_lines(path: Path) -> list[tuple[int, str]]:
    matches: list[tuple[int, str]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        if IGNORE_MARKER in line or IGNORED_COMMAND.match(line):
            continue
        if PSQL_COMMAND.search(line) or SQL_HEREDOC.search(line) or SQL_STATEMENT.search(line) or SQL_CONSTRUCTOR.search(line):
            matches.append((line_number, line.strip()))
    return matches


def current_inventory() -> dict[str, list[tuple[int, str]]]:
    inventory: dict[str, list[tuple[int, str]]] = {}
    for path in shell_files():
        matches = matched_lines(path)
        if matches:
            inventory[path.relative_to(ROOT).as_posix()] = matches
    return inventory


def parse_baseline(content: str, source: str) -> dict[str, int]:
    baseline: dict[str, int] = {}
    for line_number, raw_line in enumerate(content.splitlines(), 1):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) != 2 or not parts[1].isdigit():
            raise SystemExit(f"invalid baseline line {source}:{line_number}: {raw_line}")
        path, count_text = parts
        if path in baseline:
            raise SystemExit(f"duplicate baseline path at {source}:{line_number}: {path}")
        baseline[path] = int(count_text)
    return baseline


def read_baseline() -> dict[str, int]:
    if not BASELINE_FILE.is_file():
        raise SystemExit(f"missing SQL boundary baseline: {BASELINE_FILE.relative_to(ROOT)}")
    return parse_baseline(
        BASELINE_FILE.read_text(encoding="utf-8"),
        BASELINE_FILE.relative_to(ROOT).as_posix(),
    )


def read_base_baseline() -> dict[str, int] | None:
    base_ref = os.environ.get("GUARD_BASE")
    if not base_ref:
        return None
    baseline_path = BASELINE_FILE.relative_to(ROOT).as_posix()
    result = subprocess.run(
        ["git", "show", f"{base_ref}:{baseline_path}"],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    return parse_baseline(result.stdout, f"{base_ref}:{baseline_path}")


def print_inventory(inventory: dict[str, list[tuple[int, str]]]) -> None:
    for path, matches in inventory.items():
        print(f"{path}\t{len(matches)}")


def check(inventory: dict[str, list[tuple[int, str]]], baseline: dict[str, int]) -> int:
    failed = False
    for path in sorted(set(inventory) | set(baseline)):
        matches = inventory.get(path, [])
        current = len(matches)
        budget = baseline.get(path, 0)
        if current == budget:
            continue
        failed = True
        if current > budget:
            print(f"SQL/config boundary violation: {path} has {current} match(es), budget={budget}", file=sys.stderr)
            for line_number, line in matches:
                print(f"  {line_number}: {line}", file=sys.stderr)
        else:
            print(f"stale SQL boundary baseline: {path} has {current} match(es), budget={budget}; lower the baseline", file=sys.stderr)

    base_baseline = read_base_baseline()
    if base_baseline is not None:
        for path, budget in sorted(baseline.items()):
            base_budget = base_baseline.get(path, 0)
            if budget > base_budget:
                failed = True
                print(
                    f"SQL boundary baseline increase is forbidden: {path} {base_budget} -> {budget}",
                    file=sys.stderr,
                )

    if failed:
        print(
            "\n不要在 Shell 脚本中新增内联 SQL，也不要提高历史基线。"
            "将 SQL 放入对应 sql 目录，并通过 psql -v 绑定值；"
            "只有确认不是可执行 SQL 的文本才可使用 sql-boundary: ignore 注释。",
            file=sys.stderr,
        )
        return 1

    total = sum(len(matches) for matches in inventory.values())
    print(f"SQL/config boundary check passed (historical matches={total}, files={len(inventory)})")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--print-current", action="store_true", help="输出当前按文件统计，供人工下调基线")
    args = parser.parse_args()
    inventory = current_inventory()
    if args.print_current:
        print_inventory(inventory)
        return 0
    return check(inventory, read_baseline())


if __name__ == "__main__":
    raise SystemExit(main())
