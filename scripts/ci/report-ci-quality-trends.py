#!/usr/bin/env python3
"""生成测试质量快照和 Full Gate 历史趋势报告。"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def failure_category(text: str) -> str:
    lowered = text.lower()
    patterns = (
        ("timeout", ("timeout", "timed out", "awaitility")),
        ("container/environment", ("testcontainers", "docker", "container")),
        ("database", ("postgres", "sql", "flyway", "jdbc")),
        ("network", ("connection", "connectexception", "socket", "kafka")),
        ("assertion", ("assertion", "expected", "assertthat")),
    )
    for category, terms in patterns:
        if any(term in lowered for term in terms):
            return category
    return "other"


def parse_reports(root: Path) -> dict[str, object]:
    totals = Counter()
    categories = Counter()
    e2e = Counter()
    files = sorted(root.glob("**/TEST-*.xml"))
    for path in files:
        suite = ET.parse(path).getroot()
        tests = int(float(suite.attrib.get("tests", "0")))
        failures = int(float(suite.attrib.get("failures", "0")))
        errors = int(float(suite.attrib.get("errors", "0")))
        skipped = int(float(suite.attrib.get("skipped", "0")))
        totals.update(tests=tests, failures=failures, errors=errors, skipped=skipped)
        totals["durationSeconds"] += float(suite.attrib.get("time", "0"))
        is_e2e = "E2eIT" in path.name or "E2eIT" in suite.attrib.get("name", "")
        if is_e2e:
            e2e.update(suites=1, failedSuites=int(failures + errors > 0), tests=tests)
        for case in suite.findall("testcase"):
            flaky = case.findall("flakyFailure") + case.findall("flakyError")
            totals["flakyFirstFailures"] += len(flaky)
            for node in [*case.findall("failure"), *case.findall("error"), *flaky]:
                categories[failure_category(" ".join((node.attrib.get("message", ""), node.text or "")))] += 1
    return {
        "reportFiles": len(files),
        "tests": totals["tests"],
        "failures": totals["failures"],
        "errors": totals["errors"],
        "skipped": totals["skipped"],
        "durationSeconds": round(totals["durationSeconds"], 3),
        "flakyFirstFailures": totals["flakyFirstFailures"],
        "flakyFirstFailureRate": round(totals["flakyFirstFailures"] / totals["tests"], 6)
        if totals["tests"]
        else 0,
        "e2e": dict(e2e),
        "failureCategories": dict(categories),
    }


def parse_workflow_runs(path: Path | None) -> dict[str, object]:
    if path is None or not path.exists():
        return {"runs": 0, "successRate": None, "averageDurationSeconds": None, "conclusions": {}}
    runs = json.loads(path.read_text(encoding="utf-8")).get("workflow_runs", [])
    conclusions = Counter(run.get("conclusion") or "in_progress" for run in runs)
    durations: list[float] = []
    for run in runs:
        try:
            start = dt.datetime.fromisoformat(run["created_at"].replace("Z", "+00:00"))
            end = dt.datetime.fromisoformat(run["updated_at"].replace("Z", "+00:00"))
        except (KeyError, ValueError):
            continue
        durations.append((end - start).total_seconds())
    completed = sum(value for key, value in conclusions.items() if key != "in_progress")
    return {
        "runs": len(runs),
        "successRate": round(conclusions["success"] / completed, 4) if completed else None,
        "averageDurationSeconds": round(sum(durations) / len(durations), 1) if durations else None,
        "conclusions": dict(conclusions),
    }


def source_counts(root: Path = ROOT) -> dict[str, int]:
    disabled = 0
    flaky_quarantines = 0
    for path in root.glob("**/src/test/**/*.java"):
        source = path.read_text(encoding="utf-8")
        disabled += len(re.findall(r"@Disabled(?:\s*\(|\b)", source))
        flaky_quarantines += len(re.findall(r"@FlakyTest\s*\(", source))
    registry = json.loads((root / "docs/governance/soft-gates.json").read_text(encoding="utf-8"))
    suppression_baseline = root / "scripts/ci/java-suppression-baseline.tsv"
    suppression_exceptions = sum(
        1
        for line in suppression_baseline.read_text(encoding="utf-8").splitlines()
        if line and not line.startswith("#")
    )
    return {
        "disabledTests": disabled,
        "flakyQuarantines": flaky_quarantines,
        "suppressionExceptions": suppression_exceptions,
        "softGates": len(registry["gates"]),
    }


def markdown(report: dict[str, object]) -> str:
    tests = report["tests"]
    workflow = report["workflow"]
    e2e = tests["e2e"]
    success_rate = workflow["successRate"]
    success_text = "无历史数据" if success_rate is None else f"{success_rate:.1%}"
    duration = workflow["averageDurationSeconds"]
    duration_text = "无历史数据" if duration is None else f"{duration} 秒"
    return "\n".join(
        (
            "## CI 与测试质量趋势",
            "",
            "| 指标 | 结果 |",
            "|---|---:|",
            f"| 当前测试数 | {tests['tests']} |",
            f"| 失败 / 错误 / 跳过 | {tests['failures']} / {tests['errors']} / {tests['skipped']} |",
            f"| 测试累计耗时 | {tests['durationSeconds']} 秒 |",
            f"| 首次失败重跑 | {tests['flakyFirstFailures']} |",
            f"| E2E 套件成功 | {e2e.get('suites', 0) - e2e.get('failedSuites', 0)}/{e2e.get('suites', 0)} |",
            f"| 禁用测试 | {report['source']['disabledTests']} |",
            f"| flaky 隔离 | {report['source']['flakyQuarantines']} |",
            f"| Java suppression 例外 | {report['source']['suppressionExceptions']} |",
            f"| 软门禁 | {report['source']['softGates']} |",
            f"| 最近 Full Gate 成功率 | {success_text}（{workflow['runs']} 次） |",
            f"| 最近 Full Gate 平均耗时 | {duration_text} |",
            "",
            f"失败类型：`{json.dumps(tests['failureCategories'], ensure_ascii=False, sort_keys=True)}`",
            "",
        )
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports-root", type=Path, required=True)
    parser.add_argument("--workflow-runs-json", type=Path)
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    args = parser.parse_args()
    report = {
        "generatedAt": dt.datetime.now(dt.UTC).isoformat(),
        "tests": parse_reports(args.reports_root),
        "workflow": parse_workflow_runs(args.workflow_runs_json),
        "source": source_counts(),
    }
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rendered = markdown(report)
    args.markdown.write_text(rendered, encoding="utf-8")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as output:
            output.write(rendered)
    print(f"CI 质量趋势报告已生成：{args.markdown}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
