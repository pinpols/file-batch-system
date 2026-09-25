#!/usr/bin/env python3
"""Prevent new application/domain code from depending on concrete infrastructure SDKs."""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
BANNED_INFRASTRUCTURE_REFERENCE = re.compile(
    r"("
    r"software\.amazon\.awssdk\."
    r"|org\.springframework\.kafka\."
    r"|org\.springframework\.data\.redis\."
    r"|org\.springframework\.jdbc\."
    r"|org\.quartz\."
    r"|org\.springframework\.web\.client\."
    r"|org\.springframework\.web\.reactive\."
    r"|java\.sql\."
    r"|javax\.sql\."
    r")"
)
BANNED_IMPORT = re.compile(r"^import\s+(?:static\s+)?" + BANNED_INFRASTRUCTURE_REFERENCE.pattern)
APPLICATION_LAYERS = ("/application/", "/domain/", "/service/", "/web/")
INFRASTRUCTURE_OWNERS = (
    "/config/",
    "/infrastructure/",
    "/mapper/",
    "/mybatis/",
    "/support/",
    "/shared/client/",
    "/common/config/",
    "/common/health/",
    "/common/jdbc/",
    "/common/rls/",
    "/common/startup/",
    "/common/stateful/",
    "/common/storage/",
    "/common/tenant/",
    "/common/utils/",
)


def run_git(args: list[str]) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True)


def changed_java_files(base: str) -> list[Path]:
    output = run_git(["diff", "--name-only", f"{base}...HEAD", "--", "*.java"])
    return [
        ROOT / relative
        for relative in output.splitlines()
        if relative.endswith(".java")
        and "/src/main/java/" in relative
        and (ROOT / relative).is_file()
    ]


def is_application_boundary(path: Path) -> bool:
    relative = "/" + path.relative_to(ROOT).as_posix()
    if not any(layer in relative for layer in APPLICATION_LAYERS):
        return False
    return not any(owner in relative for owner in INFRASTRUCTURE_OWNERS)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="base ref for diff-only PR guard")
    args = parser.parse_args()

    errors: list[str] = []
    for path in changed_java_files(args.base):
        if not is_application_boundary(path):
            continue
        for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("*") or stripped.startswith("//"):
                continue
            if BANNED_IMPORT.search(stripped) or BANNED_INFRASTRUCTURE_REFERENCE.search(stripped):
                errors.append(f"{path.relative_to(ROOT)}:{line_number}: {line.strip()}")

    if errors:
        print("❌ Infrastructure abstraction boundary check failed:")
        print("Application/domain/service/web code must depend on ports or adapters, not concrete SDKs.")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("✅ Infrastructure abstraction boundary passed for changed Java main sources")
    return 0


if __name__ == "__main__":
    sys.exit(main())
