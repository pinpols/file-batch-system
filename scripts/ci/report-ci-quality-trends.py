#!/usr/bin/env python3
# 用途：生成测试质量快照和 Full Gate 历史趋势报告。
# 用法：python3 scripts/ci/report-ci-quality-trends.py --reports-root <报告目录> --json <JSON 输出> --markdown <Markdown 输出>。
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
    e2e_shards: dict[str, Counter] = {}
    artifacts: dict[str, Counter] = {}
    files = sorted(root.glob("**/TEST-*.xml"))
    for path in files:
        suite = ET.parse(path).getroot()
        tests = int(float(suite.attrib.get("tests", "0")))
        failures = int(float(suite.attrib.get("failures", "0")))
        errors = int(float(suite.attrib.get("errors", "0")))
        skipped = int(float(suite.attrib.get("skipped", "0")))
        components = path.relative_to(root).parts
        artifact = components[0] if len(components) > 1 else "(root)"
        artifact_totals = artifacts.setdefault(artifact, Counter())
        artifact_totals.update(reports=1, tests=tests, failures=failures, errors=errors, skipped=skipped)
        artifact_totals["durationSeconds"] += float(suite.attrib.get("time", "0"))
        totals.update(tests=tests, failures=failures, errors=errors, skipped=skipped)
        totals["durationSeconds"] += float(suite.attrib.get("time", "0"))
        is_e2e = "E2eIT" in path.name or "E2eIT" in suite.attrib.get("name", "")
        if is_e2e:
            failed = failures + errors > 0
            all_skipped = tests == 0 or skipped >= tests
            e2e.update(
                suites=1,
                failedSuites=int(failed),
                skippedSuites=int(not failed and all_skipped),
                successfulSuites=int(not failed and not all_skipped),
                tests=tests,
            )
            for component in path.relative_to(root).parts:
                match = re.fullmatch(r"(?:full-ci|staging-gate)-e2e-shard-(\d+)-reports", component)
                if match:
                    shard = e2e_shards.setdefault(match.group(1), Counter())
                    shard.update(suites=1, tests=tests, skipped=skipped)
                    shard["durationSeconds"] += float(suite.attrib.get("time", "0"))
                    break
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
        "artifacts": {
            key: {**value, "durationSeconds": round(value["durationSeconds"], 3)}
            for key, value in sorted(artifacts.items())
        },
        "e2eShards": {
            key: {**value, "durationSeconds": round(value["durationSeconds"], 3)}
            for key, value in sorted(e2e_shards.items(), key=lambda item: int(item[0]))
        },
        "failureCategories": dict(categories),
    }


def parse_job_results(path: Path | None) -> dict[str, str]:
    if path is None:
        return {}
    return {name: value.get("result", "unknown")
            for name, value in json.loads(path.read_text(encoding="utf-8")).items()}


def attribute_single_flat_download(jobs: dict[str, str], artifacts: dict[str, object],
                                  required: dict[str, str]) -> dict[str, object]:
    """把唯一成功任务的扁平解压报告记到它的 artifact 名下。

    actions/download-artifact 在 pattern 只命中一个 artifact 时直接解到 path，
    第一层目录是模块名而不是 artifact 名。多个成功任务时无法判断归属，保持原样。
    """
    successful = [job for job in required if jobs.get(job) == "success"]
    if len(successful) != 1 or not artifacts:
        return artifacts
    expected_names = set(required.values())
    if expected_names & set(artifacts):
        return artifacts
    merged: Counter = Counter()
    for values in artifacts.values():
        merged.update(values)
    return {required[successful[0]]: dict(merged)}


def missing_artifacts(jobs: dict[str, str], artifacts: dict[str, object], required: dict[str, str]) -> list[str]:
    return [f"{job}: {artifact}" for job, artifact in required.items()
            if jobs.get(job) == "success" and artifact not in artifacts]


def missing_e2e_artifacts(jobs: dict[str, str], artifacts: dict[str, object], workflow: Path,
                          job: str, prefix: str) -> list[str]:
    shard_ids = re.findall(r"^\s*- id:\s*(\d+)\s*$", workflow.read_text(encoding="utf-8"), re.M)
    if not shard_ids or len(set(shard_ids)) != len(shard_ids):
        raise ValueError(f"无法解析唯一 E2E 分片编号：{workflow}")
    if jobs.get(job) != "success":
        return []
    return [f"{job}: {prefix}-e2e-shard-{shard}-reports" for shard in shard_ids
            if f"{prefix}-e2e-shard-{shard}-reports" not in artifacts]


def parse_workflow_runs(path: Path | None) -> dict[str, object]:
    if path is None or not path.exists():
        return {"runs": 0, "successRate": None, "averageDurationSeconds": None, "conclusions": {}}
    runs = json.loads(path.read_text(encoding="utf-8")).get("workflow_runs", [])
    conclusions = Counter(run.get("conclusion") or "in_progress" for run in runs)
    durations: list[float] = []
    for run in runs:
        if run.get("status") != "completed" or not run.get("conclusion"):
            continue
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
    suppression_baseline = root / "scripts/ci/baselines/java-suppression-baseline.tsv"
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
            f"| 测试累计耗时（非作业墙钟） | {tests['durationSeconds']} 秒 |",
            f"| 首次失败重跑 | {tests['flakyFirstFailures']} |",
            f"| E2E 套件成功 / 失败 / 全部跳过 | {e2e.get('successfulSuites', 0)} / {e2e.get('failedSuites', 0)} / {e2e.get('skippedSuites', 0)} |",
            f"| 禁用测试 | {report['source']['disabledTests']} |",
            f"| flaky 隔离 | {report['source']['flakyQuarantines']} |",
            f"| Java suppression 例外 | {report['source']['suppressionExceptions']} |",
            f"| 软门禁 | {report['source']['softGates']} |",
            f"| 最近 Full Gate 成功率 | {success_text}（{workflow['runs']} 次） |",
            f"| 最近已完成 Full Gate 平均耗时 | {duration_text} |",
            "",
            f"失败类型：`{json.dumps(tests['failureCategories'], ensure_ascii=False, sort_keys=True)}`",
            "",
            f"测试报告文件数：{tests['reportFiles']}（为 0 时不能判定测试通过）。",
            f"成功任务缺失报告：{', '.join(report.get('missingReports', [])) or '无'}。",
            "",
            "### 测试任务状态",
            "",
            "| 任务 | 状态 |",
            "|---|---|",
            *(f"| {name} | {result} |" for name, result in sorted(report.get("jobs", {}).items())),
            "",
            "### 测试报告分片",
            "",
            "| 报告 | 文件 | 测试 / 失败 / 错误 / 跳过 | 累计秒数 |",
            "|---|---:|---|---:|",
            *(f"| {name} | {values['reports']} | {values['tests']} / {values['failures']} / "
              f"{values['errors']} / {values['skipped']} | {values['durationSeconds']} |"
              for name, values in tests.get("artifacts", {}).items()),
            "",
            "### E2E 分片测试累计耗时（非作业墙钟）",
            "",
            "| 分片 | 报告数 | 测试数 / 跳过 | 累计秒数 |",
            "|---|---:|---:|---:|",
            *(
                f"| {shard} | {values['suites']} | {values['tests']} / {values['skipped']} | {values['durationSeconds']} |"
                for shard, values in tests.get("e2eShards", {}).items()
            ),
            "",
        )
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--reports-root", type=Path, required=True)
    parser.add_argument("--workflow-runs-json", type=Path)
    parser.add_argument("--jobs-json", type=Path)
    parser.add_argument("--expect-artifact", action="append", default=[], metavar="JOB=ARTIFACT")
    parser.add_argument("--expect-e2e-workflow", type=Path)
    parser.add_argument("--expect-e2e-job")
    parser.add_argument("--expect-e2e-prefix")
    parser.add_argument("--json", type=Path, required=True)
    parser.add_argument("--markdown", type=Path, required=True)
    args = parser.parse_args()
    report = {
        "generatedAt": dt.datetime.now(dt.UTC).isoformat(),
        "tests": parse_reports(args.reports_root),
        "workflow": parse_workflow_runs(args.workflow_runs_json),
        "jobs": parse_job_results(args.jobs_json),
        "source": source_counts(),
    }
    required = {}
    for entry in args.expect_artifact:
        job, separator, artifact = entry.partition("=")
        if not separator or not job or not artifact or job in required:
            parser.error(f"无效或重复的报告映射：{entry}")
        required[job] = artifact
    report["tests"]["artifacts"] = attribute_single_flat_download(
        report["jobs"], report["tests"]["artifacts"], required)
    report["missingReports"] = missing_artifacts(report["jobs"], report["tests"]["artifacts"], required)
    e2e_options = (args.expect_e2e_workflow, args.expect_e2e_job, args.expect_e2e_prefix)
    if any(e2e_options) and not all(e2e_options):
        parser.error("E2E 报告校验需要同时提供 workflow、job 和 prefix")
    if all(e2e_options):
        report["missingReports"].extend(missing_e2e_artifacts(
            report["jobs"], report["tests"]["artifacts"], *e2e_options))
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    rendered = markdown(report)
    args.markdown.write_text(rendered, encoding="utf-8")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as output:
            output.write(rendered)
    print(f"CI 质量趋势报告已生成：{args.markdown}")
    return 1 if report["missingReports"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
