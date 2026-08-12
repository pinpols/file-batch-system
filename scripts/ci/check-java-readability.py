#!/usr/bin/env python3
"""Guard readability rules for production Java source."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOURCE_PREFIXES = (
    "batch-common/",
    "batch-console-api/",
    "batch-orchestrator/",
    "batch-trigger/",
    "batch-worker/",
    "sdk/java/",
    "security-scan/",
)
VAR_TOKEN = re.compile(r"\bvar\b")
CONFIGURATION = re.compile(r"@Configuration\b(?:\s*\((?P<arguments>[^)]*)\))?")
LITE_CONFIGURATION = re.compile(r"\bproxyBeanMethods\s*=\s*false\b")


def tracked_main_sources() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--", *SOURCE_PREFIXES],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return [
        ROOT / relative
        for relative in result.stdout.splitlines()
        if relative.endswith(".java") and "/src/main/java/" in relative
    ]


def strip_non_code(source: str) -> str:
    """Remove comments and literals while preserving line breaks and positions."""
    output: list[str] = []
    state = "code"
    index = 0
    while index < len(source):
        char = source[index]
        next_char = source[index + 1] if index + 1 < len(source) else ""
        if state == "code":
            if char == "/" and next_char == "/":
                output.extend((" ", " "))
                index += 2
                state = "line-comment"
            elif char == "/" and next_char == "*":
                output.extend((" ", " "))
                index += 2
                state = "block-comment"
            elif source.startswith('"""', index):
                output.extend((" ", " ", " "))
                index += 3
                state = "text-block"
            elif char == '"':
                output.append(" ")
                index += 1
                state = "string"
            elif char == "'":
                output.append(" ")
                index += 1
                state = "char"
            else:
                output.append(char)
                index += 1
        elif state == "line-comment":
            if char == "\n":
                output.append(char)
                state = "code"
            else:
                output.append(" ")
            index += 1
        elif state == "block-comment":
            if char == "*" and next_char == "/":
                output.extend((" ", " "))
                index += 2
                state = "code"
            else:
                output.append("\n" if char == "\n" else " ")
                index += 1
        elif state == "text-block":
            if source.startswith('"""', index):
                output.extend((" ", " ", " "))
                index += 3
                state = "code"
            else:
                output.append("\n" if char == "\n" else " ")
                index += 1
        else:
            if char == "\\" and next_char:
                output.extend((" ", " "))
                index += 2
            elif (state == "string" and char == '"') or (state == "char" and char == "'"):
                output.append(" ")
                index += 1
                state = "code"
            else:
                output.append("\n" if char == "\n" else " ")
                index += 1
    return "".join(output)


def main() -> int:
    errors: list[str] = []
    for path in tracked_main_sources():
        source = path.read_text(encoding="utf-8")
        stripped = strip_non_code(source)
        relative = path.relative_to(ROOT).as_posix()
        for match in VAR_TOKEN.finditer(stripped):
            line_number = stripped.count("\n", 0, match.start()) + 1
            errors.append(f"{relative}:{line_number}: use an explicit type instead of var")
        for match in CONFIGURATION.finditer(stripped):
            arguments = match.group("arguments") or ""
            if not LITE_CONFIGURATION.search(arguments):
                line_number = stripped.count("\n", 0, match.start()) + 1
                errors.append(
                    f"{relative}:{line_number}: declare @Configuration(proxyBeanMethods = false)"
                )

    if errors:
        print("❌ Java readability check failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("✅ Java readability check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
