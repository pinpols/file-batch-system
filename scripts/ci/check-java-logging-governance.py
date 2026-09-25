#!/usr/bin/env python3
"""Reject direct Java console writes and direct exception stack-trace printing."""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
STDOUT_WRITE = re.compile(r"\bSystem\s*\.\s*out\b")
STDERR_WRITE = re.compile(r"\bSystem\s*\.\s*err\b")
STACK_TRACE = re.compile(r"\bprintStackTrace\s*\(")
CLI_STDOUT_PREFIX = Path("security-scan/src/main/java/")


def main() -> int:
    errors: list[str] = []
    java_files: set[Path] = set()
    java_files.update(ROOT.glob("batch-*/**/src/**/*.java"))
    for root in (ROOT / "sdk", ROOT / "security-scan", ROOT / "examples"):
        if root.is_dir():
            java_files.update(root.glob("**/src/**/*.java"))
    java_files = {
        path for path in java_files if "target" not in path.relative_to(ROOT).parts
    }

    for path in sorted(java_files):
        relative = path.relative_to(ROOT)
        source = path.read_text(encoding="utf-8")
        if STACK_TRACE.search(source):
            errors.append(f"{relative}: do not print exception stacks directly; use the logging policy")
        is_cli_output = relative.as_posix().startswith(CLI_STDOUT_PREFIX.as_posix())
        if STDOUT_WRITE.search(source) and not is_cli_output:
            errors.append(
                f"{relative}: application and test code must not write directly to System.out"
            )
        if STDERR_WRITE.search(source):
            errors.append(f"{relative}: direct System.err writes are prohibited; use the logger")

    if errors:
        print("❌ Java logging governance check failed:")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(
        "✅ Java logging governance passed: direct console output is limited to the security-scan CLI; "
        "no direct stack-trace printing found"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
