#!/usr/bin/env python3
"""Reject direct Java console writes and direct exception stack-trace printing."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
STDOUT_WRITE = re.compile(r"\bSystem\s*\.\s*out\b")
STDERR_WRITE = re.compile(r"\bSystem\s*\.\s*err\b")
STACK_TRACE = re.compile(r"\bprintStackTrace\s*\(")
CLI_STDOUT_PREFIX = Path("security-scan/src/main/java/")
GATE_CODE = "JAVA_LOGGING_GOVERNANCE"
GATE_NAME = "Java 日志治理"


def java_sources(candidates: list[str] | None = None) -> set[Path]:
    if candidates is not None:
        return {
            ROOT / relative
            for relative in candidates
            if relative.endswith(".java") and (ROOT / relative).is_file()
        }
    java_files: set[Path] = set()
    java_files.update(ROOT.glob("batch-*/**/src/**/*.java"))
    for root in (ROOT / "sdk", ROOT / "security-scan", ROOT / "examples"):
        if root.is_dir():
            java_files.update(root.glob("**/src/**/*.java"))
    return {path for path in java_files if "target" not in path.relative_to(ROOT).parts}


def main(argv: list[str] | None = None) -> int:
    candidates = argv if argv else None
    errors: list[str] = []
    java_files = java_sources(candidates)

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
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(
        f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} | "
        "reason=direct console output is limited to the security-scan CLI"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
