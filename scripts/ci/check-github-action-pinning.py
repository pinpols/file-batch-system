#!/usr/bin/env python3
"""校验 GitHub Actions 外部依赖均固定到完整提交 SHA。"""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ACTION_FILES = [*ROOT.glob(".github/workflows/*.y*ml"), *ROOT.glob(".github/actions/**/action.y*ml")]
USES_PATTERN = re.compile(r"^\s*(?:-\s*)?uses:\s*['\"]?([^'\"\s#]+)['\"]?\s*(?:#\s*(.+))?$")
PINNED_PATTERN = re.compile(r"^[^/\s]+/[^@\s]+@[0-9a-f]{40}$")


def find_unpinned(files: list[Path]) -> list[str]:
    violations: list[str] = []
    for path in sorted(files):
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            match = USES_PATTERN.search(line)
            if not match:
                continue
            reference = match.group(1)
            version_comment = match.group(2)
            if reference.startswith("./"):
                continue
            if PINNED_PATTERN.fullmatch(reference) and version_comment:
                continue
            try:
                display_path = path.relative_to(ROOT)
            except ValueError:
                display_path = path
            reason = "缺少版本注释" if PINNED_PATTERN.fullmatch(reference) else "未固定 40 位 SHA"
            violations.append(f"{display_path}:{line_number}: {reference} ({reason})")
    return violations


def main() -> int:
    violations = find_unpinned(ACTION_FILES)
    if violations:
        print("GitHub Actions 外部依赖必须固定到 40 位提交 SHA，并保留版本注释：", file=sys.stderr)
        for violation in violations:
            print(f"  - {violation}", file=sys.stderr)
        return 1
    print(f"GitHub Actions SHA 固定检查通过：{len(ACTION_FILES)} 个定义文件")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
