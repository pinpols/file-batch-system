#!/usr/bin/env python3
"""Verify repo-local documentation paths referenced by backend code comments."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
CODE_PREFIXES = (
    "batch-common/",
    "batch-console-api/",
    "batch-orchestrator/",
    "batch-trigger/",
    "batch-worker/",
    "sdk/java/",
)
TEXT_SUFFIXES = {".java", ".xml", ".pom", ".yml", ".yaml", ".properties"}
PATH_PATTERN = re.compile(r"(?<![A-Za-z0-9_-])(docs/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*)")
URL_PREFIX_PATTERN = re.compile(r"https?://[^\s\"']*$")


def tracked_code_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--", *CODE_PREFIXES],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [
        ROOT / relative
        for relative in result.stdout.splitlines()
        if Path(relative).suffix in TEXT_SUFFIXES
    ]


def main() -> int:
    errors: list[str] = []
    seen: set[tuple[str, str, int]] = set()

    for path in tracked_code_files():
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            continue
        relative = path.relative_to(ROOT).as_posix()
        for line_number, line in enumerate(lines, start=1):
            for match in PATH_PATTERN.finditer(line):
                if URL_PREFIX_PATTERN.search(line[: match.start()]):
                    continue
                referenced = match.group(1).rstrip("/.,;:)]}>")
                key = (relative, referenced, line_number)
                if key in seen:
                    continue
                seen.add(key)
                if not (ROOT / referenced).exists():
                    errors.append(f"{relative}:{line_number}: missing repo path {referenced}")

    if errors:
        print("❌ code documentation reference check failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("✅ code documentation references are valid")
    return 0


if __name__ == "__main__":
    sys.exit(main())
