#!/usr/bin/env python3
"""Enforce incremental Java constructor-injection and logger conventions."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LOGGER_ALLOWLIST = {
    "batch-common/src/main/java/io/github/pinpols/batch/common/logging/SwallowedExceptionLogger.java": {
        r"LoggerFactory\s*\.\s*getLogger\s*\(\s*category\s*\)": 3,
    },
    "batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/ops/web/ConsoleApprovalController.java": {
        r'LoggerFactory\s*\.\s*getLogger\s*\(\s*"audit\.console\.approval"\s*\)': 1,
    },
    "batch-common/src/main/java/io/github/pinpols/batch/common/web/AbstractApiExceptionHandler.java": {
        r"LoggerFactory\s*\.\s*getLogger\s*\(\s*getClass\s*\(\s*\)\s*\)": 1,
    },
}
INJECTION_ANNOTATION = re.compile(r"@(Autowired|Inject|Resource)\b")
LOGGER_FACTORY = re.compile(r"\bLoggerFactory\s*\.\s*getLogger\s*\(")


def changed_paths(staged: bool, base_ref: str | None) -> list[str]:
    if staged:
        command = ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR", "-z"]
    elif base_ref:
        command = ["git", "diff", "--name-only", "--diff-filter=ACMR", "-z", f"{base_ref}...HEAD"]
    else:
        command = ["git", "ls-files", "-z"]
    result = subprocess.run(command, cwd=ROOT, check=True, capture_output=True)
    return [part.decode() for part in result.stdout.split(b"\0") if part]


def production_java(path: str) -> bool:
    normalized = f"/{path}"
    return (
        path.endswith(".java")
        and "/src/main/java/" in normalized
        and "/batch-test-support/" not in normalized
        and not normalized.startswith("/examples/")
    )


def strip_comments(source: str) -> str:
    """Remove comments while retaining string literals and line positions."""
    token = re.compile(r'("(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/)')

    def replace(match: re.Match[str]) -> str:
        value = match.group(0)
        return value if value.startswith(('"', "'")) else re.sub(r"[^\n]", " ", value)

    return token.sub(replace, source)


def mask_literals(source: str) -> str:
    literal = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    return literal.sub(lambda match: re.sub(r"[^\n]", " ", match.group(0)), source)


def find_injection_violations(source: str) -> list[str]:
    lines = source.splitlines()
    violations: list[str] = []
    for index, line in enumerate(lines):
        annotation = INJECTION_ANNOTATION.search(line)
        if not annotation:
            continue
        declaration = re.sub(r"^\([^)]*\)\s*", "", line[annotation.end() :]).strip()
        if declaration:
            end = index
        else:
            following = index + 1
            while following < len(lines) and (
                not lines[following].strip()
                or lines[following].lstrip().startswith("@")
                or lines[following].lstrip().startswith(("//", "*", "/*"))
            ):
                following += 1
            if following >= len(lines):
                continue
            declaration = lines[following].strip()
            end = following
        while end + 1 < len(lines) and ";" not in declaration and "{" not in declaration:
            end += 1
            if lines[end].lstrip().startswith("@"):
                continue
            declaration += " " + lines[end].strip()
        declaration = re.sub(r"(?:@[\w$.]+(?:\([^()]*\))?\s*)+", "", declaration).strip()
        opening_paren = declaration.find("(")
        assignment = declaration.find("=")
        has_method = opening_paren >= 0 and (assignment < 0 or opening_paren < assignment)
        has_method = has_method and ("{" in declaration or ";" in declaration)
        constructor = bool(re.match(
            r"^(?:(?:public|protected|private)\s+)?[A-Za-z_$][\w$]*\s*\(",
            declaration,
        ))
        if has_method and not constructor:
            violations.append(f"{index + 1}: method injection via @{annotation.group(1)}")
        elif ";" in declaration and not has_method:
            violations.append(f"{index + 1}: field injection via @{annotation.group(1)}")
    return violations


def find_logger_violations(path: str, source: str) -> list[str]:
    calls = list(LOGGER_FACTORY.finditer(source))
    if not calls:
        return []
    allowed = LOGGER_ALLOWLIST.get(path)
    if allowed is None:
        return ["direct LoggerFactory.getLogger; use Lombok @Slf4j or approve a precise call-site exception"]
    normalized = source
    violations: list[str] = []
    allowed_count = 0
    for pattern, expected_count in allowed.items():
        count = len(re.findall(pattern, normalized))
        allowed_count += count
        if count != expected_count:
            violations.append(f"approved logger call {pattern!r}: expected {expected_count}, found {count}")
    if len(calls) != allowed_count:
        violations.append(f"found {len(calls)} LoggerFactory.getLogger calls; only {allowed_count} approved calls are allowed")
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument("--staged", action="store_true", help="检查暂存区变更的生产 Java 文件")
    scope.add_argument("--base-ref", help="检查相对基线变更的生产 Java 文件")
    parser.add_argument("paths", nargs="*", help="显式检查的 Java 文件")
    args = parser.parse_args()

    paths = args.paths or changed_paths(args.staged, args.base_ref)
    failures: list[str] = []
    for relative in paths:
        if not production_java(relative):
            continue
        path = ROOT / relative
        if not path.is_file():
            continue
        source = strip_comments(path.read_text(encoding="utf-8"))
        failures.extend(f"{relative}:{issue}" for issue in find_injection_violations(mask_literals(source)))
        failures.extend(f"{relative}:{issue}" for issue in find_logger_violations(relative, source))

    if failures:
        print("Java 注入与 logger 规约未通过：")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    scope_name = "暂存区" if args.staged else f"基线 {args.base_ref}" if args.base_ref else "全量生产源码"
    print(f"✅ Java 注入与 logger 规约通过（{scope_name}，检查 {len(paths)} 个路径）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
