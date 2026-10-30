#!/usr/bin/env python3
# 用途：文档日期命名策略。
# 用法：在仓库根目录执行 python3 scripts/ci/check-doc-timestamp-policy.py；参数与前置环境见下方说明及所属 README。
"""校验文档资产、增量报告和历史归档的统一命名策略。"""

from __future__ import annotations

import re
import subprocess
import sys
from datetime import date
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "DOC_TIMESTAMP_POLICY"
GATE_NAME = "文档日期命名策略"
DATE_RE = re.compile(r"\d{4}-\d{2}-\d{2}")
NAME_RE = re.compile(r"(?:[a-z0-9]+(?:-[a-z0-9]+)*|ADR-\d{3}-[a-z0-9]+(?:-[a-z0-9]+)*)\.md")
DATE_SUFFIX_RE = re.compile(r"\d{4}-\d{2}-\d{2}\.md$")
CANONICAL_FILENAMES = {"README.md", "RELEASING.md", "THIRD-PARTY-LICENSES.md"}

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
    "docs/audit/",
    "docs/plans/",
    "docs/review/",
    "docs/verifications/",
)

# 当前无例外；长期有效文档不得使用日期戳，历史快照应归档或放入报告目录。
DATED_CURRENT_DOC_ALLOWLIST: set[str] = set()

# 这些是当前跟踪入口、模板或长生命周期工作清单，不按一次性报告命名。
UNDATED_REPORT_DOC_ALLOWLIST = {
    "docs/plans/hardening-backlog.md",
    "docs/plans/todo-master.md",
}

# 阶段标记放入主题；所有日期统一为 YYYY-MM-DD。
MONTH_OR_PHASE_PLAN_ALLOWLIST: set[str] = set()


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
    for retired in ("analysis", "backlog", "spike", "test-data"):
        if (ROOT / "docs" / retired).exists():
            errors.append(f"retired docs directory must not be recreated: docs/{retired}/; use document-routing-guide.md")
    dated_count = 0
    undated_count = 0

    for path in tracked_markdown():
        if has_date(path):
            dated_count += 1
        else:
            undated_count += 1

        filename = Path(path).name
        if re.search(r"\d{4}-(?:\d{2}|h[12])\.md$", filename):
            errors.append(f"doc date must use complete YYYY-MM-DD: {path}")
        for recorded in DATE_RE.findall(filename):
            try:
                date.fromisoformat(recorded)
            except ValueError:
                errors.append(f"invalid calendar date in doc filename: {path}")
        if path.startswith("docs/archive/"):
            category = Path(path).parts[2] if len(Path(path).parts) > 3 else None
            if category and not (ROOT / "docs" / category).is_dir():
                errors.append(f"archive category must match a current docs directory: {path}")
            if re.match(r"(?i)^adr-?\d", filename):
                errors.append(f"archived supporting material should use its business topic; retain ADR links in body: {path}")
        if filename not in CANONICAL_FILENAMES:
            if not NAME_RE.fullmatch(filename):
                errors.append(f"doc filename must use English kebab-case or an ADR identifier: {path}")
            if re.match(r"^\d{4}-", filename):
                errors.append(f"doc date must follow the topic, not precede it: {path}")
            if has_date(path) and not DATE_SUFFIX_RE.search(filename):
                errors.append(f"doc date must be at the end of the filename: {path}")
            if is_archive(path) and not DATE_SUFFIX_RE.search(filename):
                errors.append(f"archived snapshot must retain its original date: {path}")

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
