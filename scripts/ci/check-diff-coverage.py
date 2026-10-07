#!/usr/bin/env python3
"""使用 JaCoCo XML 校验本次变更新增/修改的可执行 Java 行覆盖率。"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
HUNK = re.compile(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@")


def changed_lines(base: str, paths: list[str], root: Path = ROOT) -> dict[Path, set[int]]:
    command = ["git", "diff", "--unified=0", "--no-ext-diff", f"{base}...HEAD", "--", *paths]
    output = subprocess.run(command, cwd=root, check=True, text=True, capture_output=True).stdout
    result: dict[Path, set[int]] = {}
    current: Path | None = None
    for line in output.splitlines():
        if line.startswith("+++ b/"):
            current = Path(line[6:])
            continue
        match = HUNK.match(line)
        if not match or current is None:
            continue
        start = int(match.group(1))
        count = int(match.group(2) or "1")
        result.setdefault(current, set()).update(range(start, start + count))
    return result


def jacoco_lines(report: Path, root: Path = ROOT) -> dict[Path, dict[int, bool]]:
    module = report.parents[3]
    result: dict[Path, dict[int, bool]] = {}
    tree = ET.parse(report)
    for package in tree.getroot().findall("package"):
        package_name = package.attrib["name"]
        for source in package.findall("sourcefile"):
            relative = module / "src/main/java" / package_name / source.attrib["name"]
            try:
                source_path = relative.relative_to(root)
            except ValueError:
                source_path = relative
            entries = result.setdefault(source_path, {})
            for line in source.findall("line"):
                entries[int(line.attrib["nr"])] = int(line.attrib.get("ci", "0")) > 0
    return result


def evaluate(changed: dict[Path, set[int]], coverage: dict[Path, dict[int, bool]]) -> tuple[int, int]:
    covered = total = 0
    for path, lines in changed.items():
        executable = coverage.get(path, {})
        for line_number in lines:
            if line_number not in executable:
                continue
            total += 1
            covered += int(executable[line_number])
    return covered, total


def uncovered_lines(
    changed: dict[Path, set[int]], coverage: dict[Path, dict[int, bool]]
) -> dict[Path, list[int]]:
    result: dict[Path, list[int]] = {}
    for path, lines in changed.items():
        executable = coverage.get(path, {})
        missed = [line for line in sorted(lines) if line in executable and not executable[line]]
        if missed:
            result[path] = missed
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--path", action="append", required=True, dest="paths")
    parser.add_argument("--minimum", type=float, default=0.80)
    args = parser.parse_args()

    changed = changed_lines(args.base, args.paths)
    reports = [report for path in args.paths for report in (ROOT / path).glob("target/site/jacoco/jacoco.xml")]
    changed_java = [path for path, lines in changed.items() if lines and "src/main/java" in path.as_posix()]
    if changed_java and not reports:
        print("存在生产 Java 变更，但未找到 JaCoCo XML 报告", file=sys.stderr)
        return 1
    coverage: dict[Path, dict[int, bool]] = {}
    for report in reports:
        coverage.update(jacoco_lines(report))
    covered, total = evaluate(changed, coverage)
    if total == 0:
        print("差异覆盖率检查通过：本范围没有变更的可执行 Java 行")
        return 0
    ratio = covered / total
    print(f"差异覆盖率：{covered}/{total} = {ratio:.1%}（最低 {args.minimum:.0%}）")
    if ratio < args.minimum:
        for path, lines in uncovered_lines(changed, coverage).items():
            print(f"未覆盖：{path}:{','.join(map(str, lines))}", file=sys.stderr)
        print("差异覆盖率低于阈值，请补充针对本次变更的测试", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
