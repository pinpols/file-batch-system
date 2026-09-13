#!/usr/bin/env python3
"""Require governance metadata for every Trivy vulnerability ignore entry."""

from __future__ import annotations

import argparse
from datetime import date, datetime
from pathlib import Path
import re
import sys


CVE_RE = re.compile(r"^CVE-\d{4}-\d{4,}$")
EXPIRES_RE = re.compile(r"(?:expires|expire|到期|复审|review)\s*[:： ]\s*(\d{4}-\d{2}-\d{2})", re.IGNORECASE)
OWNER_RE = re.compile(r"(?:owner|负责人)\s*[:：]\s*\S+", re.IGNORECASE)
REASON_RE = re.compile(r"(?:reason|原因|真实暴露|说明)\s*[:：]\s*\S+", re.IGNORECASE)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", default=".trivyignore", help="Trivy ignore file path")
    parser.add_argument("--today", help="Override current date for deterministic tests")
    return parser.parse_args()


def parse_today(raw: str | None) -> date:
    if raw:
        return datetime.strptime(raw, "%Y-%m-%d").date()
    return date.today()


def main() -> int:
    args = parse_args()
    path = Path(args.file)
    today = parse_today(args.today)
    if not path.exists():
        print(f"Trivy ignore expiry guard skipped: {path} 不存在。")
        return 0

    errors: list[str] = []
    comment_block: list[str] = []
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = raw.strip()
        if not stripped:
            comment_block = []
            continue
        if stripped.startswith("#"):
            comment_block.append(stripped.lstrip("#").strip())
            continue
        if not CVE_RE.match(stripped):
            continue

        metadata = "\n".join(comment_block)
        expiry_match = EXPIRES_RE.search(metadata)
        if not expiry_match:
            errors.append(f"{path}:{line_no}: {stripped} 缺少 expires/review 到期日期")
            continue
        expiry = datetime.strptime(expiry_match.group(1), "%Y-%m-%d").date()
        if expiry < today:
            errors.append(f"{path}:{line_no}: {stripped} 已过期，expires={expiry.isoformat()}")
        if not OWNER_RE.search(metadata):
            errors.append(f"{path}:{line_no}: {stripped} 缺少 owner")
        if not REASON_RE.search(metadata):
            errors.append(f"{path}:{line_no}: {stripped} 缺少 reason")

    if errors:
        print("Trivy ignore 白名单治理失败：", file=sys.stderr)
        for error in errors:
            print(f"  {error}", file=sys.stderr)
        print("每组 CVE 前需要 owner、reason、expires: YYYY-MM-DD。", file=sys.stderr)
        return 1

    print("Trivy ignore expiry guard passed: 所有 CVE 白名单均有 owner/reason/expires 且未过期。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
