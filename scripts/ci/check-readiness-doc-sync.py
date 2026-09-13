#!/usr/bin/env python3
"""Guard readiness contract changes against undocumented drift.

The readiness path is a cross-module contract: trigger scheduling, asset
partition state, OpenAPI, and operator docs must move together. This check is
intentionally path based and diff-only; semantic validation still belongs in
unit, IT, and e2e coverage.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import re
import subprocess
import sys


READINESS_TOUCHPOINTS = (
    re.compile(r"(^|/)readiness(/|[-_.A-Za-z0-9]*\.)", re.IGNORECASE),
    re.compile(r"Readiness"),
    re.compile(r"AssetPartition"),
    re.compile(r"ResultVersion"),
    re.compile(r"asset_partition|result_version|readiness", re.IGNORECASE),
    re.compile(r"HashedWheelTriggerScheduler|TriggerRuntimeState|DefaultTriggerService"),
)

EVIDENCE_PATHS = (
    re.compile(r"^docs/design/asset-partition-readiness\.md$"),
    re.compile(r"^docs/api/(orchestrator-internal|console-api)\.openapi\.yaml$"),
    re.compile(r"^docs/api/console-api-protocol\.md$"),
    re.compile(r"^docs/(runbook|testing)/.*readiness.*\.md$", re.IGNORECASE),
    re.compile(r"(^|/)src/test/.*Readiness.*(Test|IT)\.java$"),
    re.compile(r"^batch-e2e-tests/.*Readiness.*(Test|IT)\.java$"),
)

CODE_OR_CONTRACT_PATHS = (
    ".java",
    ".xml",
    ".yaml",
    ".yml",
    ".sql",
)


CONTENT_TOUCHPOINTS = (
    re.compile(r"\breadiness\b", re.IGNORECASE),
    re.compile(r"\basset_partition\b", re.IGNORECASE),
    re.compile(r"\bresult_version\b", re.IGNORECASE),
)


def run_git(args: list[str]) -> str:
    result = subprocess.run(["git", *args], check=True, capture_output=True, text=True)
    return result.stdout


def changed_files(base: str | None) -> list[str]:
    command = ["diff", "--name-only"]
    if base:
        command.append(f"{base}...HEAD")
    else:
        command.append("HEAD")
    try:
        output = run_git(command)
    except subprocess.CalledProcessError as exc:
        print(f"无法读取 git diff: {exc}", file=sys.stderr)
        return []
    return [line.strip() for line in output.splitlines() if line.strip()]


def diff_contains_touchpoint(path: str, base: str | None) -> bool:
    command = ["diff", "--unified=0"]
    if base:
        command.append(f"{base}...HEAD")
    else:
        command.append("HEAD")
    command.extend(["--", path])
    try:
        diff = run_git(command)
    except subprocess.CalledProcessError:
        return False
    for line in diff.splitlines():
        if line.startswith(("+++", "---", "@@")):
            continue
        if not line.startswith(("+", "-")):
            continue
        if any(pattern.search(line[1:]) for pattern in CONTENT_TOUCHPOINTS):
            return True
    return False


def is_readiness_touchpoint(path: str, base: str | None) -> bool:
    if path.startswith(("docs/", ".github/")):
        return False
    if not path.endswith(CODE_OR_CONTRACT_PATHS):
        return False
    if any(pattern.search(path) for pattern in READINESS_TOUCHPOINTS):
        return True
    if diff_contains_touchpoint(path, base):
        return True

    # Fallback for callers without a diffable base and newly edited working-tree files.
    if base:
        return False
    file_path = Path(path)
    if not file_path.is_file():
        return False
    try:
        content = file_path.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return False
    return any(pattern.search(content) for pattern in CONTENT_TOUCHPOINTS)


def is_evidence(path: str) -> bool:
    return any(pattern.search(path) for pattern in EVIDENCE_PATHS)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", help="比较 base...HEAD；不传时检查 HEAD 到当前工作树的改动")
    args = parser.parse_args()

    files = changed_files(args.base)
    readiness_files = [path for path in files if is_readiness_touchpoint(path, args.base)]
    if not readiness_files:
        print("Readiness doc sync guard passed: 未发现 readiness 契约触点变更。")
        return 0

    evidence_files = [path for path in files if is_evidence(path)]
    if evidence_files:
        print("Readiness doc sync guard passed: 已同步文档/API/测试证据。")
        return 0

    print("readiness 相关契约变更缺少同步证据：", file=sys.stderr)
    for path in readiness_files:
        print(f"  {path}", file=sys.stderr)
    print("请同步以下至少一类文件后再提交：", file=sys.stderr)
    print("  docs/design/asset-partition-readiness.md", file=sys.stderr)
    print("  docs/api/orchestrator-internal.openapi.yaml 或 docs/api/console-api.openapi.yaml", file=sys.stderr)
    print("  docs/api/console-api-protocol.md", file=sys.stderr)
    print("  readiness 相关 Test/IT/e2e 用例", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
