#!/usr/bin/env python3
"""阻止本机配置、生成物和个人绝对路径进入版本控制。"""

from __future__ import annotations

import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ALLOWED_BUILD_FILES = {"build/pmd-ruleset.xml", "build/spotbugs-npe-filter.xml"}
TEXT_SUFFIXES = {".java", ".kt", ".py", ".sh", ".md", ".xml", ".yml", ".yaml", ".properties", ".toml", ".json"}
ABSOLUTE_PATH = re.compile(r"/Users/|/var/folders/|[A-Za-z]:\\\\Users\\\\")
PATTERN_DEFINITION_FILES = {
    "scripts/ci/check-hardcoded-runtime-config.sh",
    "scripts/ci/check-repository-hygiene.py",
}


def versionable_files() -> list[str]:
    output = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT,
        text=True,
    )
    return [line for line in output.splitlines() if line]


def main() -> int:
    errors: list[str] = []
    for relative in versionable_files():
        path = Path(relative)
        if path.name == ".DS_Store" or path.name.startswith("settings.local."):
            errors.append(f"tracked local metadata: {relative}")
        if relative in {".env", ".env.local", ".env.test", ".env.prod"}:
            errors.append(f"tracked environment secret file: {relative}")
        if relative.startswith(("logs/", "reports/", "target/")) or "/target/" in relative:
            errors.append(f"tracked generated output: {relative}")
        if relative.startswith("build/") and relative not in ALLOWED_BUILD_FILES:
            errors.append(f"tracked build output: {relative}")

        if path.suffix not in TEXT_SUFFIXES or relative.startswith("docs/archive/"):
            continue
        if relative in PATTERN_DEFINITION_FILES:
            continue
        try:
            content = (ROOT / relative).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for line_number, line in enumerate(content.splitlines(), 1):
            if ABSOLUTE_PATH.search(line):
                errors.append(f"personal absolute path: {relative}:{line_number}")

    if errors:
        print("Repository hygiene guard failed:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("Repository hygiene guard passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
