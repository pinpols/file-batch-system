#!/usr/bin/env python3
"""校验软门禁均登记责任人、基线与升级期限。"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REGISTRY = ROOT / "docs/governance/soft-gates.json"
MARKER = re.compile(r"soft-gate-id:\s*([A-Z0-9_]+)")
NON_GATE_MARKER = "non-gate-failure-policy:"


def load_registry(path: Path) -> list[dict[str, str]]:
    return json.loads(path.read_text(encoding="utf-8"))["gates"]


def validate(today: dt.date, root: Path = ROOT, registry: Path = REGISTRY) -> list[str]:
    violations: list[str] = []
    entries = load_registry(registry)
    by_id = {entry.get("id", ""): entry for entry in entries}
    if len(by_id) != len(entries):
        violations.append("软门禁 ID 为空或重复")

    marker_ids: set[str] = set()
    candidates = [root / "pom.xml", *root.glob(".github/workflows/*.y*ml")]
    for path in candidates:
        lines = path.read_text(encoding="utf-8").splitlines()
        for index, line in enumerate(lines):
            marker_ids.update(MARKER.findall(line))
            if "continue-on-error: true" not in line and "<failOnError>false</failOnError>" not in line:
                continue
            context = "\n".join(lines[max(0, index - 5) : index + 1])
            if not MARKER.search(context) and NON_GATE_MARKER not in context:
                violations.append(f"{path.relative_to(root)}:{index + 1}: 未标明软门禁或非门禁容错策略")

    for gate_id, entry in by_id.items():
        for field in ("owner", "baseline", "promotionDeadline", "target"):
            if not str(entry.get(field, "")).strip():
                violations.append(f"{gate_id}: 缺少 {field}")
        if not str(entry.get("owner", "")).startswith("@"):
            violations.append(f"{gate_id}: owner 必须使用 @账号或 @团队")
        try:
            deadline = dt.date.fromisoformat(str(entry.get("promotionDeadline", "")))
        except ValueError:
            violations.append(f"{gate_id}: promotionDeadline 必须是 YYYY-MM-DD")
            continue
        if deadline < today:
            violations.append(f"{gate_id}: 升级期限 {deadline} 已过期")

    for missing in sorted(set(by_id) - marker_ids):
        violations.append(f"{missing}: 注册项未在配置中使用")
    for unknown in sorted(marker_ids - set(by_id)):
        violations.append(f"{unknown}: 配置标记未登记")
    return violations


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--today", type=dt.date.fromisoformat, default=dt.date.today())
    args = parser.parse_args()
    violations = validate(args.today)
    if violations:
        print("软门禁治理检查失败：", file=sys.stderr)
        for violation in violations:
            print(f"  - {violation}", file=sys.stderr)
        return 1
    print(f"软门禁治理检查通过：{len(load_registry(REGISTRY))} 项")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
