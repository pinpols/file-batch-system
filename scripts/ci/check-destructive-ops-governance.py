#!/usr/bin/env python3
"""Detect growth of repository scripts that can delete local or service data."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BASELINE = ROOT / "scripts/ci/destructive-ops-baseline.json"
SCAN_ROOTS = ("scripts", "load-tests/scripts", "db", "deploy", ".github/workflows", "docs/runbook")
EXTENSIONS = {".sh", ".sql", ".py", ".yml", ".yaml", ".md"}
PATTERNS = {
    "filesystem-remove": re.compile(r"(?<![\w-])rm(?=\s|$)"),
    "filesystem-permanent-delete": re.compile(
        r"\b(?:find\b.*\s-delete\b|rmdir\b|unlink\b|shred\b|wipefs\b|mkfs(?:\.[A-Za-z0-9_-]+)?\b|sfdisk\b|fdisk\b)"
        r"|\bdd\b.*\bof=/dev/",
        re.I,
    ),
    "database-drop": re.compile(
        r"\bDROP\s+(?:DATABASE|SCHEMA|TABLE|INDEX|CONSTRAINT|OWNED|TABLESPACE)\b"
        r"|\b(?:dropdb|dropuser|pg_resetwal)\b",
        re.I,
    ),
    "database-truncate": re.compile(r"\bTRUNCATE\s+(?:TABLE\s+)?", re.I),
    "database-delete": re.compile(r"\bDELETE\s+FROM\s+", re.I),
    "postgres-clean-restore": re.compile(
        r"\bpg_restore\b.*(?:\B--clean\b|(?:^|\s)-c(?:\s|$))"
        r"|(?:\B--clean\b|(?:^|\s)-c(?:\s|$)).*\bpg_restore\b",
        re.I,
    ),
    "s3-delete": re.compile(
        r"\b(?:mc|minio_mc)\s+(?:rm|rb)\b"
        r"|\baws\s+s3\s+rm\b"
        r"|\baws\s+s3\s+sync\b.*\s--delete\b"
        r"|\baws\s+s3api\s+delete-(?:bucket|object|objects)\b"
        r"|\bs3api\s+delete-(?:bucket|object|objects)\b"
        r"|\brclone\s+(?:delete|purge)\b"
        r"|\bs5cmd\s+rm\b"
        r"|\bmc\s+mirror\b.*\s--remove\b",
        re.I,
    ),
    "kafka-delete": re.compile(
        r"\bkafka-topics(?:\.sh)?\b.*\B--delete\b|\B--delete\b.*\bkafka-topics(?:\.sh)?\b"
        r"|\bkafka-consumer-groups(?:\.sh)?\b.*\B--delete\b|\B--delete\b.*\bkafka-consumer-groups(?:\.sh)?\b"
        r"|\bkafka-delete-records(?:\.sh)?\b",
        re.I,
    ),
    "redis-delete": re.compile(
        r"\b(?:redis-cli|valkey-cli)\b.*\b(?:FLUSHALL|FLUSHDB|DEL|UNLINK)\b"
        r"|\b(?:FLUSHALL|FLUSHDB|DEL|UNLINK)\b.*\b(?:redis-cli|valkey-cli)\b",
        re.I,
    ),
    "docker-delete": re.compile(
        r"\bdocker\s+(?:(?:container|volume|network|image)\s+rm|rm\b|(?:system|builder|image|volume)\s+prune)\b",
        re.I,
    ),
    "kubernetes-delete": re.compile(r"\bkubectl\b.*\bdelete\b|\bhelm\b.*\buninstall\b|\bterraform\b.*\bdestroy\b", re.I),
}


def scan(root: Path) -> Counter[str]:
    counts: Counter[str] = Counter()
    for relative_root in SCAN_ROOTS:
        scan_root = root / relative_root
        if not scan_root.exists():
            continue
        for path in sorted(scan_root.rglob("*")):
            if not path.is_file() or path.suffix.lower() not in EXTENSIONS:
                continue
            relative_path = path.relative_to(root).as_posix()
            if relative_path == "scripts/ci/check-destructive-ops-governance.py" or relative_path.startswith("scripts/ci/tests/"):
                continue
            comment = "--" if path.suffix.lower() == ".sql" else "#"
            logical_lines: list[str] = []
            pending = ""
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                code = line.split(comment, 1)[0].rstrip()
                if code.endswith("\\"):
                    pending += code[:-1] + " "
                    continue
                logical_lines.append(pending + code)
                pending = ""
            if pending:
                logical_lines.append(pending)
            for code in logical_lines:
                for category, pattern in PATTERNS.items():
                    matches = pattern.findall(code)
                    if matches:
                        counts[f"{relative_path}|{category}"] += len(matches)
    return counts


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--write-baseline", action="store_true")
    args = parser.parse_args()

    current = scan(args.root.resolve())
    if args.write_baseline:
        if args.root.resolve() == ROOT and not args.baseline.resolve().is_relative_to(ROOT):
            print("拒绝将仓库基线写到仓库外。", file=sys.stderr)
            return 2
        args.baseline.parent.mkdir(parents=True, exist_ok=True)
        args.baseline.write_text(
            json.dumps(dict(sorted(current.items())), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"已写入危险操作基线:{args.baseline} ({sum(current.values())} 项 / {len(current)} 个路径类别)")
        return 0

    try:
        baseline = json.loads(args.baseline.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"危险操作基线不可读:{error}", file=sys.stderr)
        return 2
    if not isinstance(baseline, dict) or any(not isinstance(value, int) for value in baseline.values()):
        print("危险操作基线格式错误:期望 {\"path|category\": count}。", file=sys.stderr)
        return 2

    growth = {
        key: (baseline.get(key, 0), count)
        for key, count in current.items()
        if count > baseline.get(key, 0)
    }
    if growth:
        print("发现新增或扩大的高风险操作，必须先审查目标范围、确认机制和恢复方案：", file=sys.stderr)
        for key, (old, new) in sorted(growth.items()):
            print(f"  {key}: {old} -> {new}", file=sys.stderr)
        print("审查通过后才可更新 scripts/ci/destructive-ops-baseline.json，并同步治理文档。", file=sys.stderr)
        return 1

    stale = sorted(set(baseline) - set(current))
    print(
        f"高风险操作基线未增长：{sum(current.values())} 项 / {len(current)} 个路径类别。"
        + (f" 已移除类别: {len(stale)}。" if stale else "")
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
