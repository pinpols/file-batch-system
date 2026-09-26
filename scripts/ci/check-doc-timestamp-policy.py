#!/usr/bin/env python3
"""校验 docs 文档日期命名和历史归档策略。"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "DOC_TIMESTAMP_POLICY"
GATE_NAME = "文档日期命名策略"
DATE_RE = re.compile(r"\d{4}-\d{2}(?:-\d{2})?")

CURRENT_DOC_PREFIXES = (
    "docs/api/",
    "docs/architecture/",
    "docs/design/",
    "docs/governance/",
    "docs/runbook/",
    "docs/sdk/",
    "docs/standards/",
    "docs/testing/",
)
REPORT_DOC_PREFIXES = (
    "docs/analysis/",
    "docs/audit/",
    "docs/backlog/",
    "docs/plans/",
    "docs/review/",
    "docs/verifications/",
)

# 已存在的历史材料。它们保留原路径以避免破坏链接，但不得继续新增同类文件。
DATED_CURRENT_DOC_ALLOWLIST = {
    "docs/architecture/deficiencies-2026-05-30.md",
    "docs/architecture/runtime-compatibility-contract-2026-09-01.md",
    "docs/design/file-integrity-sidecar-manifest-design-2026-06-07.md",
    "docs/design/import-export-column-mapping-default-inference-2026-06-20.md",
    "docs/runbook/ci-cd-followup-2026-05-22.md",
    "docs/runbook/ci-speedup-2026-06-02.md",
    "docs/runbook/e2e-it-optimization-2026-05-22.md",
    "docs/runbook/gitops-onboarding-2026-05-22.md",
    "docs/runbook/go-live-realism-audit-2026-06-21.md",
    "docs/runbook/sql-audit-2026-05-20.md",
    "docs/runbook/tia-poc-2026-05-22.md",
    "docs/sdk/conformance-gap-analysis-2026-06-16.md",
}

# 这些是当前跟踪入口、模板或长生命周期工作清单，不按一次性报告命名。
UNDATED_REPORT_DOC_ALLOWLIST = {
    "docs/analysis/deep-issue-analysis.md",
    "docs/analysis/fix-report.md",
    "docs/analysis/hardening-backlog.md",
    "docs/analysis/industry-benchmark-improvement-plan.md",
    "docs/analysis/project-assessment.md",
    "docs/analysis/system-scope-boundary.md",
    "docs/analysis/todo-master.md",
    "docs/audit/convention-drift-guard-index.md",
    "docs/backlog/be-acceptance-template.md",
}

# 阶段性计划按半年度或月度管理，允许 YYYY-MM / YYYY-h2 等非日粒度。
MONTH_OR_PHASE_PLAN_ALLOWLIST = {
    "docs/plans/ai-integration-plan-2026-07.md",
    "docs/plans/alertmanager-migration-plan-2026-07.md",
    "docs/plans/backend-borrowings-and-improvements-2026-07.md",
    "docs/plans/checkpoint-resume-design-2026-07.md",
    "docs/plans/fe-worklist-2026-h2-atomic-sdk.md",
    "docs/plans/ipv6-happy-eyeballs-rollout-2026-09.md",
    "docs/plans/r3-1-chaos-toxiproxy-it.md",
    "docs/plans/r3-2-runbook-playbooks.md",
    "docs/plans/r3-3-soak-tests.md",
    "docs/plans/r3-4-forensic-replay.md",
    "docs/plans/sdk-roadmap-2026-h2-progress.md",
    "docs/plans/sdk-roadmap-2026-h2.md",
}


def tracked_markdown() -> list[str]:
    output = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "docs"],
        cwd=ROOT,
        text=True,
    )
    return sorted(
        line
        for line in output.splitlines()
        if line.endswith(".md") and (ROOT / line).is_file()
    )


def is_archive(path: str) -> bool:
    return "/archive/" in path or path.startswith("docs/archive/")


def has_date(path: str) -> bool:
    return bool(DATE_RE.search(Path(path).name))


def under_any(path: str, prefixes: tuple[str, ...]) -> bool:
    return any(path.startswith(prefix) for prefix in prefixes)


def main() -> int:
    errors: list[str] = []
    dated_count = 0
    undated_count = 0

    for path in tracked_markdown():
        if has_date(path):
            dated_count += 1
        else:
            undated_count += 1

        if is_archive(path) or path.endswith("/README.md"):
            continue

        if under_any(path, CURRENT_DOC_PREFIXES) and has_date(path):
            if path not in DATED_CURRENT_DOC_ALLOWLIST:
                errors.append(f"current doc should not use dated filename: {path}")

        if under_any(path, REPORT_DOC_PREFIXES) and not has_date(path):
            if path not in UNDATED_REPORT_DOC_ALLOWLIST and path not in MONTH_OR_PHASE_PLAN_ALLOWLIST:
                errors.append(f"report/plan doc should use dated filename or be allowlisted: {path}")

    missing_allowlisted = sorted(
        (DATED_CURRENT_DOC_ALLOWLIST | UNDATED_REPORT_DOC_ALLOWLIST | MONTH_OR_PHASE_PLAN_ALLOWLIST)
        - set(tracked_markdown())
    )
    for path in missing_allowlisted:
        errors.append(f"timestamp policy allowlist references missing doc: {path}")

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1

    print(
        f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} "
        f"(dated={dated_count}, undated={undated_count}, current-dated-legacy={len(DATED_CURRENT_DOC_ALLOWLIST)})"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
