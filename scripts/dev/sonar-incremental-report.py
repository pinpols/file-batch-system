#!/usr/bin/env python3
"""从 Sonar 全量分析结果中导出 Git 变更行级增量报告。"""

from __future__ import annotations

import argparse
import base64
import csv
import json
import re
import subprocess
import urllib.parse
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Iterable


HUNK_PATTERN = re.compile(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def run_git(root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout


def merge_ranges(ranges: Iterable[tuple[int, int]]) -> list[tuple[int, int]]:
    merged: list[list[int]] = []
    for start, end in sorted(ranges):
        if not merged or start > merged[-1][1] + 1:
            merged.append([start, end])
        else:
            merged[-1][1] = max(merged[-1][1], end)
    return [(start, end) for start, end in merged]


def parse_changed_lines(diff_text: str) -> dict[str, list[tuple[int, int]]]:
    changed: dict[str, list[tuple[int, int]]] = {}
    current_path: str | None = None
    for line in diff_text.splitlines():
        if line.startswith("+++ "):
            value = line[4:].split("\t", 1)[0]
            current_path = None if value == "/dev/null" else value.removeprefix("b/")
            continue
        match = HUNK_PATTERN.match(line)
        if match is None or current_path is None:
            continue
        start = int(match.group(1))
        count = int(match.group(2) or "1")
        if count > 0:
            changed.setdefault(current_path, []).append((start, start + count - 1))
    return {path: merge_ranges(ranges) for path, ranges in changed.items()}


def collect_changed_lines(root: Path, base_ref: str) -> tuple[str, dict[str, list[tuple[int, int]]]]:
    merge_base = run_git(root, "merge-base", base_ref, "HEAD").strip()
    diff_text = run_git(
        root,
        "-c",
        "core.quotePath=false",
        "diff",
        "--unified=0",
        "--no-color",
        "--find-renames",
        merge_base,
        "--",
        "*.java",
    )
    changed = parse_changed_lines(diff_text)

    untracked_output = subprocess.run(
        ["git", "ls-files", "--others", "--exclude-standard", "-z", "--", "*.java"],
        cwd=root,
        check=True,
        capture_output=True,
    ).stdout
    for raw_path in untracked_output.split(b"\0"):
        if not raw_path:
            continue
        relative_path = raw_path.decode("utf-8", errors="surrogateescape")
        line_count = sum(1 for _ in (root / relative_path).open(encoding="utf-8"))
        changed[relative_path] = [(1, max(line_count, 1))]
    return merge_base, changed


def line_is_changed(line: str, ranges: list[tuple[int, int]]) -> bool:
    if not line:
        return True
    try:
        line_number = int(line)
    except ValueError:
        return True
    return any(start <= line_number <= end for start, end in ranges)


def filter_issue_rows(
    rows: Iterable[dict[str, str]], changed: dict[str, list[tuple[int, int]]]
) -> list[dict[str, str]]:
    return [
        row
        for row in rows
        if row.get("status") == "OPEN"
        and row.get("component") in changed
        and line_is_changed(row.get("line", ""), changed[row["component"]])
    ]


def sonar_get(url: str, username: str, password: str, path: str) -> dict:
    request = urllib.request.Request(url.rstrip("/") + path)
    credentials = base64.b64encode(f"{username}:{password}".encode()).decode()
    request.add_header("Authorization", f"Basic {credentials}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def fetch_hotspots(
    url: str, project_key: str, username: str, password: str
) -> list[dict[str, str]]:
    hotspots: list[dict[str, str]] = []
    page = 1
    while True:
        query = urllib.parse.urlencode(
            {"projectKey": project_key, "ps": 500, "p": page, "status": "TO_REVIEW"}
        )
        data = sonar_get(url, username, password, f"/api/hotspots/search?{query}")
        components = {item["key"]: item.get("path", "") for item in data.get("components", [])}
        for hotspot in data.get("hotspots", []):
            hotspots.append(
                {
                    "component": components.get(hotspot.get("component", ""), ""),
                    "line": str(hotspot.get("line", "")),
                    "rule": hotspot.get("ruleKey", ""),
                    "category": hotspot.get("securityCategory", ""),
                    "probability": hotspot.get("vulnerabilityProbability", ""),
                    "message": hotspot.get("message", ""),
                    "status": hotspot.get("status", ""),
                }
            )
        paging = data.get("paging", {})
        if page * paging.get("pageSize", 500) >= paging.get("total", 0):
            break
        page += 1
    return hotspots


def filter_hotspots(
    hotspots: Iterable[dict[str, str]], changed: dict[str, list[tuple[int, int]]]
) -> list[dict[str, str]]:
    return [
        hotspot
        for hotspot in hotspots
        if hotspot.get("component") in changed
        and line_is_changed(hotspot.get("line", ""), changed[hotspot["component"]])
    ]


def escape_markdown(value: str) -> str:
    return value.replace("|", "\\|").replace("\n", " ")


def write_report(
    output_dir: Path,
    base_ref: str,
    merge_base: str,
    changed: dict[str, list[tuple[int, int]]],
    issues: list[dict[str, str]],
    hotspots: list[dict[str, str]],
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    changed_json = output_dir / "sonar-changed-lines.json"
    changed_json.write_text(
        json.dumps(
            {
                "baseRef": base_ref,
                "mergeBase": merge_base,
                "files": {
                    path: [{"start": start, "end": end} for start, end in ranges]
                    for path, ranges in sorted(changed.items())
                },
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    issue_fields = ["severity", "type", "component", "line", "rule", "message", "status", "effort"]
    with (output_dir / "sonar-incremental-report.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=issue_fields)
        writer.writeheader()
        writer.writerows({field: row.get(field, "") for field in issue_fields} for row in issues)

    hotspot_fields = ["component", "line", "rule", "category", "probability", "message", "status"]
    with (output_dir / "sonar-incremental-hotspots.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=hotspot_fields)
        writer.writeheader()
        writer.writerows({field: row.get(field, "") for field in hotspot_fields} for row in hotspots)

    severities = Counter(issue.get("severity", "UNKNOWN") for issue in issues)
    report = output_dir / "sonar-incremental-report.md"
    with report.open("w", encoding="utf-8") as file:
        file.write("# SonarQube Incremental Report\n\n")
        file.write(f"- Git 基线：`{base_ref}` (`{merge_base}`)\n")
        file.write(f"- Java 变更文件：{len(changed)}\n")
        file.write(f"- 变更行 OPEN Issue：{len(issues)}\n")
        file.write(f"- 变更行待审 Security Hotspot：{len(hotspots)}\n\n")
        file.write("## Issue 分布\n\n")
        file.write("| BLOCKER | CRITICAL | MAJOR | MINOR | INFO |\n")
        file.write("|---:|---:|---:|---:|---:|\n")
        file.write(
            "| " + " | ".join(str(severities[name]) for name in ("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")) + " |\n\n"
        )
        if issues:
            file.write("## 增量 Issue\n\n")
            file.write("| 严重度 | 类型 | 文件 | 行 | 规则 | 描述 |\n")
            file.write("|---|---|---|---:|---|---|\n")
            for issue in issues:
                file.write(
                    f"| {issue.get('severity', '')} | {issue.get('type', '')} | "
                    f"`{issue.get('component', '')}` | {issue.get('line', '')} | "
                    f"{issue.get('rule', '')} | {escape_markdown(issue.get('message', ''))} |\n"
                )
        if hotspots:
            file.write("\n## 增量 Security Hotspot\n\n")
            file.write("| 文件 | 行 | 规则 | 类别 | 描述 |\n")
            file.write("|---|---:|---|---|---|\n")
            for hotspot in hotspots:
                file.write(
                    f"| `{hotspot.get('component', '')}` | {hotspot.get('line', '')} | "
                    f"{hotspot.get('rule', '')} | {hotspot.get('category', '')} | "
                    f"{escape_markdown(hotspot.get('message', ''))} |\n"
                )
        file.write("\n---\n")
        file.write("仅统计 merge-base 到当前工作树的新增/修改 Java 行；删除行不会产生当前代码 Issue。\n")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--issues-csv", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--sonar-url", required=True)
    parser.add_argument("--project-key", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    args = parser.parse_args()

    merge_base, changed = collect_changed_lines(args.root.resolve(), args.base_ref)
    with args.issues_csv.open(newline="", encoding="utf-8") as file:
        issues = filter_issue_rows(csv.DictReader(file), changed)
    hotspots = filter_hotspots(
        fetch_hotspots(args.sonar_url, args.project_key, args.username, args.password),
        changed,
    )
    write_report(args.output_dir, args.base_ref, merge_base, changed, issues, hotspots)
    print(
        f"Incremental report: {len(changed)} Java file(s), "
        f"{len(issues)} open issue(s), {len(hotspots)} hotspot(s)"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
