#!/usr/bin/env python3
# 用途：软门禁治理。
# 用法：在仓库根目录执行 python3 scripts/ci/check-soft-gate-governance.py；参数与前置环境见下方说明及所属 README。
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
RUNNER_MODE = re.compile(r"run-gate\.sh[^\n]*(?:--mode\s+|BATCH_GATE_MODE=)(report|ignore)\b")
RUNNER_GROUP = re.compile(r"run-gate\.sh[^\n]*--group\s+([a-z0-9][a-z0-9-]*)\b")
GROUP_NAME = re.compile(r"^[a-z0-9][a-z0-9-]*$")
ALLOWED_MODES = {"report", "ignore"}
ALLOWED_CONTROLS = {"run-gate", "workflow-continue-on-error", "tool-native"}
LEGACY_GATE_SKIP = re.compile(r"BATCH_CI_SKIP_[A-Z0-9_]*GATE")
WORKFLOW_MODE_BYPASS = re.compile(
    r"\bBATCH_GATE_ENABLED\s*[:=]\s*['\"]?(?:0|false|no|off)\b"
    r"|\bBATCH_GATE_MODE\s*[:=]\s*['\"]?(?:report|ignore)\b"
    r"|\bBATCH_GATE_(?:GROUP_)?MODES\s*[:=].*(?:report|ignore)"
)


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
            if WORKFLOW_MODE_BYPASS.search(line):
                violations.append(
                    f"{path.relative_to(root)}:{index + 1}: workflow 禁止通过环境变量弱化门禁，使用带登记的 run-gate --mode"
                )
            if "continue-on-error: true" not in line and "<failOnError>false</failOnError>" not in line:
                if not RUNNER_MODE.search(line):
                    continue
            context = "\n".join(lines[max(0, index - 5) : index + 1])
            marker = MARKER.search(context)
            if not marker and NON_GATE_MARKER not in context:
                violations.append(f"{path.relative_to(root)}:{index + 1}: 未登记报告/忽略模式或非门禁容错策略")
                continue
            if marker and RUNNER_MODE.search(line):
                gate_id = marker.group(1)
                configured_mode = RUNNER_MODE.search(line).group(1)
                registered_mode = str(by_id.get(gate_id, {}).get("mode", ""))
                if configured_mode != registered_mode:
                    violations.append(
                        f"{path.relative_to(root)}:{index + 1}: {gate_id} 配置模式 {configured_mode} 与登记 {registered_mode} 不一致"
                    )
                configured_group = RUNNER_GROUP.search(line)
                registered_group = str(by_id.get(gate_id, {}).get("group", ""))
                if not configured_group or configured_group.group(1) != registered_group:
                    violations.append(
                        f"{path.relative_to(root)}:{index + 1}: {gate_id} 调用分组与登记 {registered_group} 不一致"
                    )

    control_sources = [
        *root.glob("scripts/ci/**/*.py"),
        *root.glob("scripts/ci/**/*.sh"),
        *root.glob("scripts/local/**/*.sh"),
    ]
    for path in control_sources:
        if path.resolve() == Path(__file__).resolve() or "tests" in path.relative_to(root).parts:
            continue
        for index, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            if LEGACY_GATE_SKIP.search(line):
                violations.append(
                    f"{path.relative_to(root)}:{index}: 禁止脚本自定义 BATCH_CI_SKIP_*GATE，使用统一门禁模式"
                )

    for gate_id, entry in by_id.items():
        for field in ("group", "mode", "control", "owner", "baseline", "promotionDeadline", "target"):
            if not str(entry.get(field, "")).strip():
                violations.append(f"{gate_id}: 缺少 {field}")
        if entry.get("mode") not in ALLOWED_MODES:
            violations.append(f"{gate_id}: mode 必须是 report 或 ignore")
        if not GROUP_NAME.fullmatch(str(entry.get("group", ""))):
            violations.append(f"{gate_id}: group 必须是小写短横线标识")
        if entry.get("control") not in ALLOWED_CONTROLS:
            violations.append(f"{gate_id}: control 不是受支持的统一控制方式")
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
    print(f"门禁报告/忽略模式治理检查通过：{len(load_registry(REGISTRY))} 项")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
