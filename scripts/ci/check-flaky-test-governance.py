#!/usr/bin/env python3
"""校验 flaky 测试隔离项的责任人、Issue 和到期日。"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ANNOTATION = re.compile(r"@FlakyTest\s*\((.*?)\)", re.DOTALL)
FIELD = re.compile(r'\b(issue|owner|expiresOn)\s*=\s*"([^"]+)"')


def find_violations(root: Path, today: dt.date) -> list[str]:
    violations: list[str] = []
    for path in root.glob("**/src/test/**/*.java"):
        text = path.read_text(encoding="utf-8")
        for match in ANNOTATION.finditer(text):
            fields = dict(FIELD.findall(match.group(1)))
            location = path.relative_to(root)
            missing = {"issue", "owner", "expiresOn"} - fields.keys()
            if missing:
                violations.append(f"{location}: 缺少 {', '.join(sorted(missing))}")
                continue
            if not (fields["issue"].startswith("http") or fields["issue"].startswith("#")):
                violations.append(f"{location}: issue 必须是 URL 或 #编号")
            if not fields["owner"].startswith("@"):
                violations.append(f"{location}: owner 必须使用 @账号或 @团队")
            try:
                expires_on = dt.date.fromisoformat(fields["expiresOn"])
            except ValueError:
                violations.append(f"{location}: expiresOn 必须是 YYYY-MM-DD")
                continue
            if expires_on < today:
                violations.append(f"{location}: flaky 隔离已于 {expires_on} 到期")
    return violations


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--today", type=dt.date.fromisoformat, default=dt.date.today())
    args = parser.parse_args()
    violations = find_violations(ROOT, args.today)
    if violations:
        print("flaky 测试隔离治理检查失败：", file=sys.stderr)
        for violation in violations:
            print(f"  - {violation}", file=sys.stderr)
        return 1
    count = sum(
        len(ANNOTATION.findall(path.read_text(encoding="utf-8")))
        for path in ROOT.glob("**/src/test/**/*.java")
    )
    print(f"flaky 测试隔离治理检查通过：{count} 个隔离项")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
