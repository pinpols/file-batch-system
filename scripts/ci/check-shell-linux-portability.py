#!/usr/bin/env python3
"""Guard shell scripts from macOS/BSD-only commands without Linux fallbacks."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]


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
        "osascript is macOS-only; keep it out of repository automation.",
    ),
)


def tracked_shell_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "--", "*.sh"],
        cwd=ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
    )
    return [ROOT / line for line in result.stdout.splitlines() if line.strip()]


def is_comment_or_blank(line: str) -> bool:
    stripped = line.strip()
    return not stripped or stripped.startswith("#")


def scan_file(path: Path) -> list[str]:
    errors: list[str] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if is_comment_or_blank(line):
            continue
        for rule in RULES:
            if not rule.pattern.search(line):
                continue
            if any(allow.search(line) for allow in rule.allow_if_line_matches):
                continue
            errors.append(
                f"{path.relative_to(ROOT)}:{line_number}: {rule.name}: {rule.message}"
            )
    return errors


def main() -> int:
    errors: list[str] = []
    for path in tracked_shell_files():
        errors.extend(scan_file(path))
    if errors:
        print("❌ Shell Linux portability guard failed:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("✅ Shell Linux portability guard passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
