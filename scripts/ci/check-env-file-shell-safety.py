#!/usr/bin/env python3
"""Validate tracked env files that are sourced by local/CI shell scripts."""

from __future__ import annotations

from pathlib import Path
import re
import subprocess
import sys


ASSIGNMENT = re.compile(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$")


def tracked_env_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", ".env*"],
        check=True,
        capture_output=True,
        text=True,
    )
    return [Path(line) for line in result.stdout.splitlines() if line.strip()]


def is_quoted(value: str) -> bool:
    return len(value) >= 2 and (
        (value.startswith('"') and value.endswith('"'))
        or (value.startswith("'") and value.endswith("'"))
    )


def main() -> int:
    errors: list[str] = []
    for path in tracked_env_files():
        if not path.is_file():
            continue
        for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            stripped = raw.strip()
            if not stripped or stripped.startswith("#"):
                continue
            match = ASSIGNMENT.match(raw)
            if not match:
                continue
            key = match.group(1)
            value = match.group(2).strip()
            if not value or is_quoted(value):
                continue
            if any(char.isspace() for char in value):
                errors.append(
                    f"{path}:{line_no}: {key} 的值包含空白,需要整体加引号: {raw.strip()}"
                )

    if errors:
        print("env 文件 shell-safe 检查失败：", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        print("这些文件会被本地/CI 脚本 source；带空白的值必须写成 KEY=\"...\"。", file=sys.stderr)
        return 1

    print("Env shell-safety guard passed: tracked env files are source-safe.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
