#!/usr/bin/env python3
"""Prevent new application/domain code from depending on concrete infrastructure SDKs."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "INFRASTRUCTURE_ABSTRACTION_BOUNDARY"
GATE_NAME = "基础设施抽象边界"
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


def infrastructure_references(content: str) -> list[tuple[int, str]]:
    references = []
    for line_number, line in enumerate(content.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith(("*", "//")):
            continue
        if BANNED_INFRASTRUCTURE_REFERENCE.search(stripped):
            references.append((line_number, stripped))
    return references


def new_references(before: str, after: str) -> list[tuple[int, str]]:
    # 以基线引用及出现次数抵扣既有项,日志修复不能变成整文件历史债务阻断。
    baseline = Counter(re.sub(r"\s+", "", line) for _, line in infrastructure_references(before))
    added = []
    for line_number, line in infrastructure_references(after):
        key = re.sub(r"\s+", "", line)
        if baseline[key]:
            baseline[key] -= 1
        else:
            added.append((line_number, line))
    return added


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True, help="base ref for diff-only PR guard")
    args = parser.parse_args()
    merge_base = run_git(["merge-base", args.base, "HEAD"]).strip()

    errors: list[str] = []
    for path in changed_java_files(args.base):
        if not is_application_boundary(path):
            continue
        relative = path.relative_to(ROOT).as_posix()
        baseline = subprocess.run(
            ["git", "show", f"{merge_base}:{relative}"], cwd=ROOT, capture_output=True, text=True
        )
        # 新增文件没有基线;其他读取错误必须失败,不能静默丢失比较依据。
        before = baseline.stdout
        if baseline.returncode and run_git(["ls-tree", "--name-only", merge_base, "--", relative]).strip():
            raise RuntimeError(f"cannot read infrastructure boundary baseline: {relative}")
        for line_number, line in new_references(before, path.read_text(encoding="utf-8")):
            errors.append(f"{relative}:{line_number}: {line}")

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        print("Application/domain/service/web code must depend on ports or adapters, not concrete SDKs.")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
