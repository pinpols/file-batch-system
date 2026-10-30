#!/usr/bin/env python3
# 用途：治理基线扩张审批。
# 用法：在仓库根目录执行 python3 scripts/ci/check-governance-baseline-expansion.py；参数与前置环境见下方说明及所属 README。
"""治理基线增长必须有明确且有期限的审批记录。"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BASELINE_FILES = (
    "docs/governance/direct-config-key-access-baseline.txt",
    "docs/governance/test-conventions-baseline.txt",
    "docs/governance/java-contract-governance-baseline.json",
)
APPROVALS_FILE = "docs/governance/baseline-expansion-approvals.json"
GATE_CODE = "GOVERNANCE_BASELINE_EXPANSION"


def git_show(root: Path, ref: str, path: str) -> str:
    result = subprocess.run(
        ["git", "show", f"{ref}:{path}"],
        cwd=root,
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError(f"cannot read {ref}:{path}: {result.stderr.strip()}")
    return result.stdout


def text_entries(content: str) -> set[str]:
    return {line.strip() for line in content.splitlines() if line.strip() and not line.lstrip().startswith("#")}


def parse_baseline_items(content: str, path: str, source: str | None = None) -> dict[str, str]:
    diagnostic_source = source or path
    if path.endswith(".txt"):
        return {entry: "present" for entry in text_entries(content)}
    document = json.loads(content)
    counts = document.get("counts")
    if document.get("schema_version") != 1 or not isinstance(counts, dict):
        raise ValueError(f"{diagnostic_source}: expected schema_version=1 and counts object")
    items: dict[str, str] = {}
    for key, value in counts.items():
        if not isinstance(key, str) or not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise ValueError(f"{diagnostic_source}: invalid baseline count {key!r}={value!r}")
        items[f"counts:{key}"] = str(value)
    return items


def baseline_items(root: Path, path: str) -> dict[str, str]:
    content = (root / path).read_text(encoding="utf-8")
    return parse_baseline_items(content, path)


def base_items(root: Path, ref: str, path: str) -> dict[str, str]:
    content = git_show(root, ref, path)
    return parse_baseline_items(content, path, f"{ref}:{path}")


def check(root: Path, base_ref: str, today: date | None = None) -> list[str]:
    today = today or date.today()
    changed: dict[tuple[str, str], str] = {}
    for path in BASELINE_FILES:
        before = base_items(root, base_ref, path)
        after = baseline_items(root, path)
        for item, value in after.items():
            old_value = before.get(item)
            if old_value is None or (path.endswith(".json") and int(value) > int(old_value)):
                changed[(path, item)] = value

    manifest_path = root / APPROVALS_FILE
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("schemaVersion") != 1 or not isinstance(manifest.get("approvals"), list):
        return [f"{APPROVALS_FILE}: expected schemaVersion=1 and approvals array"]

    errors: list[str] = []
    approved: dict[tuple[str, str], dict] = {}
    current = {path: baseline_items(root, path) for path in BASELINE_FILES}
    for record in manifest["approvals"]:
        if not isinstance(record, dict):
            errors.append("approval entries must be objects")
            continue
        path, item = record.get("baseline"), record.get("item")
        key = (path, item)
        if path not in BASELINE_FILES or not isinstance(item, str) or key in approved:
            errors.append(f"invalid or duplicate approval key: {key!r}")
            continue
        required = ("owner", "reason", "reviewBy", "removalCondition")
        missing = [name for name in required if not isinstance(record.get(name), str) or not record[name].strip()]
        if missing:
            errors.append(f"{path} {item}: missing {', '.join(missing)}")
            continue
        try:
            review_by = date.fromisoformat(record["reviewBy"])
        except ValueError:
            errors.append(f"{path} {item}: reviewBy must be YYYY-MM-DD")
            continue
        if review_by < today:
            errors.append(f"{path} {item}: approval expired on {review_by}")
        if item not in current[path]:
            errors.append(f"{path} {item}: approval is stale; remove it with the baseline entry")
        elif path.endswith(".json") and str(record.get("value")) != current[path][item]:
            errors.append(f"{path} {item}: approved value does not match current baseline")
        approved[key] = record

    for (path, item), value in changed.items():
        record = approved.get((path, item))
        if record is None:
            errors.append(f"unapproved baseline expansion: {path}::{item}")
            continue
        if path.endswith(".json") and str(record.get("value")) != value:
            errors.append(f"{path}::{item}: approval value must be {value}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--base-ref",
        default=f"origin/{os.environ.get('GITHUB_BASE_REF', 'main')}",
        help="git ref containing the governance baseline before this change",
    )
    args = parser.parse_args()
    try:
        errors = check(ROOT, args.base_ref)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        errors = [str(error)]
    if errors:
        for error in errors:
            print(f"❌ 不通过 | code={GATE_CODE} | {error}", file=sys.stderr)
        print(
            f"请在 {APPROVALS_FILE} 登记 owner、reason、reviewBy 和 removalCondition；"
            "基线文件同时由 CODEOWNERS 路由审查。",
            file=sys.stderr,
        )
        return 1
    print(f"✅ 通过 | code={GATE_CODE} | base={args.base_ref} | 未发现未审批的基线扩张")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
