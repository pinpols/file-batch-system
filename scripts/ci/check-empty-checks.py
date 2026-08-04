#!/usr/bin/env python3
"""阻止生产 Java 新增零散的 null / empty / blank 判断。

统一判断入口是 ``EmptyChecks``。本检查只拦本次 diff 新增的生产代码，避免把历史
遗留一次性大改，也避免把有明确语义的 ``Objects.equals``、``Optional`` 等误判为
空值判断。历史代码由业务迁移逐步收口；从本检查接入后不得继续新增同类写法。

用法：
  python3 scripts/ci/check-empty-checks.py
  python3 scripts/ci/check-empty-checks.py --base origin/main
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys


DIRECT_CHECK = re.compile(
    r"(?:\.isEmpty\s*\(\)|\.isBlank\s*\(\)|"
    r"\.size\s*\(\)\s*==\s*0|\.length\s*==\s*0)"
)


def is_production_java(path: str) -> bool:
    return path.endswith(".java") and "/src/main/java/" in f"/{path}"


def added_lines(base: str | None) -> list[tuple[str, int, str]]:
    command = ["git", "diff", "--unified=0"]
    if base:
        command.append(f"{base}...HEAD")
    else:
        # 本地默认检查 HEAD 到工作树，包含 staged 和 unstaged 修改。
        command.append("HEAD")
    command.extend(["--", "*.java"])
    result = subprocess.run(command, check=True, capture_output=True, text=True)

    path = None
    line_no = 0
    additions: list[tuple[str, int, str]] = []
    for raw in result.stdout.splitlines():
        if raw.startswith("+++ b/"):
            path = raw[6:]
            continue
        if raw.startswith("@@"):
            match = re.search(r"\+([0-9]+)", raw)
            line_no = int(match.group(1)) if match else 0
            continue
        if raw.startswith("+") and not raw.startswith("+++"):
            if path and is_production_java(path):
                additions.append((path, line_no, raw[1:]))
            line_no += 1
        elif not raw.startswith("-") and line_no:
            line_no += 1
    return additions


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base",
        help="比较 base...HEAD；不传时检查 HEAD 到当前工作树的改动",
    )
    args = parser.parse_args()

    violations = []
    for path, line_no, source in added_lines(args.base):
        if path.endswith("/EmptyChecks.java"):
            continue
        stripped = source.strip()
        if not stripped or stripped.startswith(("//", "/*", "*")):
            continue
        if "EmptyChecks." in source:
            continue
        if DIRECT_CHECK.search(source):
            violations.append((path, line_no, stripped))

    if violations:
        print("发现新增零散空值判断，请改用 EmptyChecks：", file=sys.stderr)
        for path, line_no, source in violations:
            print(f"  {path}:{line_no}: {source}", file=sys.stderr)
        return 1

    print("EmptyChecks guard passed: 新增生产代码未发现零散空值判断。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
