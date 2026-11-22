#!/usr/bin/env python3
# 用途：检查 Shell 脚本是否使用 macOS 自带 Bash 3.2 不支持的语法。
# 用法：在仓库根目录执行 python3 scripts/ci/check-shell-bash32-compat.py；参数与前置环境见下方说明及所属 README。
"""本地开发同时支持 macOS（Bash 3.2）与 CI Linux（Bash 5），脚本默认不得使用 Bash 4+ 语法。

需要 Bash 4+ 的脚本可在头部声明 ``# shell-min-version: 4`` 显式豁免。
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "SHELL_BASH32_COMPAT"
GATE_NAME = "Shell Bash 3.2 兼容性"
EXEMPTION = re.compile(r"^#\s*shell-min-version:\s*4\b", re.MULTILINE)
EXEMPTION_SCAN_LINES = 15
EXTRA_SHELL_FILES = (".githooks/pre-commit", ".githooks/pre-push")


@dataclass(frozen=True)
class Rule:
    name: str
    pattern: re.Pattern[str]
    message: str


RULES = (
    Rule(
        "lowercase-expansion",
        re.compile(r"\$\{[A-Za-z_][A-Za-z0-9_]*,,}"),
        "Bash 4 ${var,,} is unavailable in Bash 3.2; use tr '[:upper:]' '[:lower:]'.",
    ),
    Rule(
        "uppercase-expansion",
        re.compile(r"\$\{[A-Za-z_][A-Za-z0-9_]*\^\^}"),
        "Bash 4 ${var^^} is unavailable in Bash 3.2; use tr '[:lower:]' '[:upper:]'.",
    ),
    Rule(
        "mapfile",
        re.compile(r"\bmapfile\b"),
        "mapfile is Bash 4+; collect with a while read loop instead.",
    ),
    Rule(
        "readarray",
        re.compile(r"\breadarray\b"),
        "readarray is Bash 4+; collect with a while read loop instead.",
    ),
    Rule(
        "associative-array",
        re.compile(r"\b(declare|local|typeset)\s+-[A-Za-z]*A(\s|$)"),
        "Associative arrays are Bash 4+; use indexed arrays or another lookup.",
    ),
)


def tracked_shell_files(candidates: list[str] | None = None) -> list[Path]:
    if candidates is not None:
        paths = [
            ROOT / relative
            for relative in candidates
            if relative.endswith(".sh") and (ROOT / relative).is_file()
        ]
    else:
        result = subprocess.run(
            ["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*.sh"],
            cwd=ROOT,
            check=True,
            text=True,
            stdout=subprocess.PIPE,
        )
        paths = [
            ROOT / line
            for line in result.stdout.splitlines()
            if line.strip() and (ROOT / line).is_file()
        ]
    for relative in EXTRA_SHELL_FILES:
        path = ROOT / relative
        if path.is_file() and path not in paths:
            paths.append(path)
    return paths


def is_comment_or_blank(line: str) -> bool:
    stripped = line.strip()
    return not stripped or stripped.startswith("#")


def is_exempt(lines: list[str]) -> bool:
    return bool(EXEMPTION.search("\n".join(lines[:EXEMPTION_SCAN_LINES])))


def scan_file(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    if is_exempt(lines):
        return []
    errors: list[str] = []
    for line_number, line in enumerate(lines, start=1):
        if is_comment_or_blank(line):
            continue
        for rule in RULES:
            if rule.pattern.search(line):
                errors.append(
                    f"{path.relative_to(ROOT)}:{line_number}: {rule.name}: {rule.message}"
                )
    return errors


def main(argv: list[str] | None = None) -> int:
    candidates = argv if argv else None
    paths = tracked_shell_files(candidates)
    errors: list[str] = []
    for path in paths:
        errors.extend(scan_file(path))
    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} | files={len(paths)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
