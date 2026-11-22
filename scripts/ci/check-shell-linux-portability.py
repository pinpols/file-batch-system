#!/usr/bin/env python3
# 用途：Shell Linux 兼容性。
# 用法：在仓库根目录执行 python3 scripts/ci/check-shell-linux-portability.py；参数与前置环境见下方说明及所属 README。
"""检查 Shell 脚本是否使用缺少 Linux 替代方案的 macOS/BSD 专属命令。"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "SHELL_LINUX_PORTABILITY"
GATE_NAME = "Shell Linux 兼容性"


@dataclass(frozen=True)
class Rule:
    name: str
    pattern: re.Pattern[str]
    message: str
    allow_if_line_matches: tuple[re.Pattern[str], ...] = ()


RULES = (
    Rule(
        "bsd-sed-in-place",
        re.compile(r"\bsed\s+-i\s+(['\"]{2}|''|\"\")"),
        "Use a portable temp file + mv helper instead of BSD-only sed -i ''.",
    ),
    Rule(
        "bsd-date-v",
        re.compile(r"\bdate\s+-v[+-]?\d"),
        "Use Python date arithmetic or a helper instead of BSD-only date -v.",
    ),
    Rule(
        "bsd-stat-f-without-linux-fallback",
        re.compile(r"\bstat\s+-f\b"),
        "Pair BSD stat -f with a Linux stat -c fallback on the same command path.",
        allow_if_line_matches=(re.compile(r"\|\|\s*stat\s+-c\b"),),
    ),
    Rule(
        "macos-open",
        re.compile(r"\bopen\s+(-a\b|\")"),
        "macOS open is not available on Linux runners; guard it behind an OS check.",
    ),
    Rule(
        "pbcopy-pbpaste",
        re.compile(r"\b(pbcopy|pbpaste)\b"),
        "pbcopy/pbpaste are macOS-only; keep them out of repository automation.",
    ),
    Rule(
        "osascript",
        re.compile(r"\bosascript\b"),
        "osascript is macOS-only; keep them out of repository automation.",
    ),
    Rule(
        "gnu-xargs-r",
        re.compile(r"\bxargs\b[^|;\n]*\s-r\b"),
        "BSD xargs (macOS) does not support -r; loop over input instead.",
    ),
)

# 有 Linux 等价物的 macOS 工具：允许保留 macOS 分支，但同一文件必须给出 Linux 回退。
TOOL_FALLBACK_RULES = (
    (
        "shasum-sha256-without-fallback",
        re.compile(r"\bshasum\s+-a\s+256\b"),
        re.compile(r"\bsha256sum\b"),
        "Use sha256sum on Linux and keep shasum only as fallback.",
    ),
    (
        "shasum-sha512-without-fallback",
        re.compile(r"\bshasum\s+-a\s+512\b"),
        re.compile(r"\bsha512sum\b"),
        "Use sha512sum on Linux and keep shasum only as fallback.",
    ),
    (
        "shasum-without-linux-fallback",
        re.compile(r"\bshasum\b"),
        re.compile(r"\b(sha1sum|sha256sum|sha512sum)\b"),
        "shasum is missing from many Linux images; prefer sha*sum with a shasum fallback.",
    ),
    (
        "md5-without-md5sum",
        re.compile(r"\bmd5\s+-[A-Za-z]"),
        re.compile(r"\bmd5sum\b"),
        "md5 is macOS-only; use md5sum with an md5 fallback.",
    ),
)

EXTRA_SHELL_FILES = (".githooks/pre-commit", ".githooks/pre-push")


def tracked_shell_files(candidates: list[str] | None = None) -> list[Path]:
    paths: list[Path]
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
        # Git 索引仍可能包含工作树中已删除的脚本，检查对象以现存文件为准。
        paths = [
            ROOT / line
            for line in result.stdout.splitlines()
            if line.strip() and (ROOT / line).is_file()
        ]
    # Git 钩子没有 .sh 扩展名，但同样按 Shell 执行。
    for relative in EXTRA_SHELL_FILES:
        path = ROOT / relative
        if path.is_file() and path not in paths:
            paths.append(path)
    return paths


def is_comment_or_blank(line: str) -> bool:
    stripped = line.strip()
    return not stripped or stripped.startswith("#")


def first_matching_line(lines: list[str], pattern: re.Pattern[str]) -> int:
    for line_number, line in enumerate(lines, start=1):
        if pattern.search(line):
            return line_number
    return 1


def scan_file(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    errors: list[str] = []
    relative = path.relative_to(ROOT)
    for line_number, line in enumerate(lines, start=1):
        if is_comment_or_blank(line):
            continue
        for rule in RULES:
            if not rule.pattern.search(line):
                continue
            if any(allow.search(line) for allow in rule.allow_if_line_matches):
                continue
            errors.append(f"{relative}:{line_number}: {rule.name}: {rule.message}")
    for name, usage, fallback, message in TOOL_FALLBACK_RULES:
        if usage.search(text) and not fallback.search(text):
            line_number = first_matching_line(lines, usage)
            errors.append(f"{relative}:{line_number}: {name}: {message}")
    return errors


def main(argv: list[str] | None = None) -> int:
    candidates = argv if argv else None
    errors: list[str] = []
    for path in tracked_shell_files(candidates):
        errors.extend(scan_file(path))
    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
