#!/usr/bin/env python3
"""Guard Java text block layout without fighting Spotless."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def tracked_java_sources() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--", "*.java"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [ROOT / line for line in result.stdout.splitlines() if line.endswith(".java")]


def closing_delimiter_has_content(line: str) -> bool:
    before_delimiter = line.split('"""', 1)[0]
    return bool(before_delimiter.strip())


def scan_file(path: Path) -> list[str]:
    errors: list[str] = []
    lines = path.read_text(encoding="utf-8").splitlines()
    in_text_block = False
    start_line = 0

    for line_number, line in enumerate(lines, start=1):
        delimiter_count = line.count('"""')
        if not in_text_block:
            if delimiter_count:
                in_text_block = True
                start_line = line_number
                if delimiter_count >= 2:
                    in_text_block = False
            continue

        if delimiter_count:
            if closing_delimiter_has_content(line):
                relative = path.relative_to(ROOT).as_posix()
                errors.append(
                    f"{relative}:{start_line}-{line_number}: put the closing text block delimiter on its own line"
                )
            in_text_block = False

    return errors


def main() -> int:
    errors: list[str] = []
    for path in tracked_java_sources():
        errors.extend(scan_file(path))

    if errors:
        print("❌ Java text block style check failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("✅ Java text block style check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
