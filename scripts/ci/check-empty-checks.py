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


CHECK_PATTERNS = (
    (
        "null 判断",
        re.compile(r"(?:(?<![=!])(?:==|!=)\s*null\b|\bnull\s*(?:==|!=)(?![=]))"),
    ),
    ("字符串 / 集合空判断", re.compile(r"(?:\.isEmpty\s*\(\)|\.isBlank\s*\(\))")),
    (
        "集合大小判断",
        re.compile(r"(?:\.size\s*\(\)\s*(?:==|!=|>|>=|<|<=)\s*0|\b0\s*(?:==|!=|<|<=|>|>=)\s*[^;]*\.size\s*\(\))"),
    ),
    (
        "数组长度判断",
        re.compile(r"(?:\.length\s*(?:==|!=|>|>=|<|<=)\s*0|\b0\s*(?:==|!=|<|<=|>|>=)\s*[^;]*\.length\b)"),
    ),
    (
        "分散工具类空判断",
        re.compile(
            r"\b(?:CollectionUtils|StringUtils|ObjectUtils)\."
            r"(?:isEmpty|isNotEmpty|isBlank|isNotBlank|hasText|hasLength)\s*\("
        ),
    ),
)

ALLOW_MARKER = re.compile(r"empty-check:\s*allow(?:\s*-\s*(?P<reason>\S.*))?")
ALLOWED_IDIOMS = (
    "Objects.requireNonNull",
    "Objects.requireNonNullElse",
    "Objects.requireNonNullElseGet",
)


def is_production_java(path: str) -> bool:
    return path.endswith(".java") and "/src/main/java/" in f"/{path}"


def is_pure_merge_revert(base: str | None) -> bool:
    """Do not treat restored pre-merge lines as additions in a pure merge revert."""
    if not base:
        return False
    try:
        base_revision = subprocess.run(
            ["git", "rev-parse", "--verify", base],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
        head_parents = subprocess.run(
            ["git", "rev-list", "--parents", "-n1", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.split()

        # pull_request checkouts normally point at a synthetic merge commit.
        candidates = ["HEAD"]
        if len(head_parents) == 3:
            candidates = head_parents[1:]
    except subprocess.CalledProcessError:
        return False

    for candidate in candidates:
        try:
            candidate_parents = subprocess.run(
                ["git", "rev-list", "--parents", "-n1", candidate],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.split()
            subject = subprocess.run(
                ["git", "log", "-1", "--format=%s", candidate],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
        except subprocess.CalledProcessError:
            continue
        if (
            len(candidate_parents) == 2
            and candidate_parents[1] == base_revision
            and subject.startswith('Revert "Merge ')
        ):
            return True
    return False


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

    if is_pure_merge_revert(args.base):
        print("EmptyChecks guard passed: pure merge revert does not add production code.")
        return 0

    violations = []
    bad_allows = []
    for path, line_no, source in added_lines(args.base):
        if path.endswith("/EmptyChecks.java"):
            continue
        stripped = source.strip()
        if not stripped or stripped.startswith(("//", "/*", "*")):
            continue
        allow = ALLOW_MARKER.search(source)
        if allow:
            if not allow.group("reason"):
                bad_allows.append((path, line_no, stripped))
            continue
        if "EmptyChecks." in source:
            continue
        if any(idiom in source for idiom in ALLOWED_IDIOMS):
            continue
        for label, pattern in CHECK_PATTERNS:
            if pattern.search(source):
                violations.append((path, line_no, label, stripped))
                break

    if bad_allows:
        print("empty-check 豁免必须写明原因：", file=sys.stderr)
        for path, line_no, source in bad_allows:
            print(f"  {path}:{line_no}: {source}", file=sys.stderr)
        return 1

    if violations:
        print("发现新增零散空值判断，请改用 EmptyChecks：", file=sys.stderr)
        for path, line_no, label, source in violations:
            print(f"  {path}:{line_no}: [{label}] {source}", file=sys.stderr)
        print("确需保留原生判断时，在同一行追加：// empty-check: allow - 原因", file=sys.stderr)
        return 1

    print("EmptyChecks guard passed: 新增生产代码未发现零散空值判断。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
