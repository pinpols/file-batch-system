#!/usr/bin/env python3
"""collect-flaky.py — 扫所有模块 surefire / failsafe XML,汇总 flaky 用例。

背景:pom.xml 配 `rerunFailingTestsCount=2`,首次 fail 后再跑 2 次,
任一过即标 flaky-but-pass(不染绿)。surefire 把这些用例记成
`<flakyFailure>` / `<flakyError>` 子节点,但本仓此前没汇总 → 飘的用例
没人盯,容易在主干上堆积。

本脚本:
- 扫 `**/target/surefire-reports/*.xml` 与 `**/target/failsafe-reports/*.xml`
- 解析 testsuite/testcase,提 flakyFailure / flakyError(可能多个 → 重试次数)
- 输出:
  - stdout 人读 summary(模块 / 类#方法 / 重试次数 / 首条错误摘要)
  - 若 `GITHUB_STEP_SUMMARY` env 存在,追加 Markdown 表(给 GH Actions UI)
  - 若 `--json <path>` 指定,写机读 JSON(留给后续巡检 trend / 阈值告警)
- 退出码恒为 0(flaky 本就允许 pass);可选 `--warn-threshold N` 在
  count > N 时打 WARN(stderr),仍不阻断 build —— 阻断逻辑应放在治理 issue,
  不放 CI gate(避免主干 red over noise)。

详细治理流程见 `docs/runbook/ci.md` 「flaky 治理」小节。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Iterable


REPO_ROOT = Path(__file__).resolve().parents[2]


def find_report_files(root: Path) -> Iterable[Path]:
    """所有模块 target/{surefire,failsafe}-reports/*.xml,跳 TEST-summary。"""
    for pattern in ("**/target/surefire-reports/TEST-*.xml",
                    "**/target/failsafe-reports/TEST-*.xml"):
        yield from root.glob(pattern)


def _rel_to(path: Path, base: Path) -> str:
    try:
        return str(path.relative_to(base))
    except ValueError:
        return str(path)


def _first_text(element: ET.Element, max_len: int = 240) -> str:
    """取 <flakyFailure>/<flakyError> 节点首行 text(stack head 一句话即可)。"""
    raw = (element.text or "").strip()
    if not raw:
        msg = element.get("message") or element.get("type") or ""
        raw = msg.strip()
    first_line = raw.splitlines()[0] if raw else ""
    if len(first_line) > max_len:
        first_line = first_line[: max_len - 1] + "…"
    return first_line


def parse_report(xml_path: Path, base: Path) -> list[dict]:
    """单个 XML → 该文件里所有 flaky case 列表。"""
    try:
        tree = ET.parse(xml_path)
    except ET.ParseError as exc:
        # 报告损坏不是 flaky 范畴,只在 stderr 提示一行,继续扫别的
        print(f"WARN: cannot parse {xml_path}: {exc}", file=sys.stderr)
        return []

    root = tree.getroot()
    # 推断 module:.../<module>/target/(sure|fail)safe-reports/TEST-*.xml
    rel = _rel_to(xml_path, base)
    rel_parts = Path(rel).parts
    module = rel_parts[0] if rel_parts and rel_parts[0] != xml_path.anchor else "?"

    results: list[dict] = []
    for case in root.iter("testcase"):
        flaky_nodes = list(case.findall("flakyFailure")) + list(case.findall("flakyError"))
        if not flaky_nodes:
            continue
        results.append(
            {
                "module": module,
                "class": case.get("classname", "?"),
                "method": case.get("name", "?"),
                "retries": len(flaky_nodes),
                "first_error": _first_text(flaky_nodes[0]),
                "report": rel,
            }
        )
    return results


def render_text(items: list[dict]) -> str:
    if not items:
        return "No flaky tests detected.\n"
    lines = [f"Flaky tests detected: {len(items)}", ""]
    for it in items:
        lines.append(f"- [{it['module']}] {it['class']}#{it['method']}  (retries={it['retries']})")
        lines.append(f"    first error: {it['first_error']}")
        lines.append(f"    report:      {it['report']}")
    lines.append("")
    return "\n".join(lines)


def render_markdown(items: list[dict]) -> str:
    if not items:
        return "## Flaky tests\n\n_No flaky tests detected._\n"
    rows = [
        "## Flaky tests",
        "",
        f"Total flaky cases: **{len(items)}**",
        "",
        "| Module | Class#Method | Retries | First error |",
        "|---|---|---:|---|",
    ]
    for it in items:
        err = it["first_error"].replace("|", "\\|")
        rows.append(
            f"| {it['module']} | `{it['class']}#{it['method']}` | {it['retries']} | {err} |"
        )
    rows.append("")
    return "\n".join(rows)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Collect flaky tests from surefire / failsafe reports.")
    parser.add_argument(
        "--root", default=str(REPO_ROOT),
        help="Repo root to scan (default: repo root).",
    )
    parser.add_argument(
        "--json", dest="json_out", default=None,
        help="Optional path to write machine-readable JSON.",
    )
    parser.add_argument(
        "--warn-threshold", type=int, default=5,
        help="Emit WARN line to stderr if flaky count exceeds this (default: 5). Never blocks build.",
    )
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    items: list[dict] = []
    for xml_path in find_report_files(root):
        items.extend(parse_report(xml_path, root))

    # stable sort: 让运维 review 时同模块同类聚一起
    items.sort(key=lambda x: (x["module"], x["class"], x["method"]))

    sys.stdout.write(render_text(items))

    if args.json_out:
        out_path = Path(args.json_out)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps({"count": len(items), "items": items}, indent=2), encoding="utf-8")

    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write(render_markdown(items))

    if len(items) > args.warn_threshold:
        print(
            f"WARN: {len(items)} flaky tests exceed threshold {args.warn_threshold}; "
            "see docs/runbook/ci.md flaky 治理.",
            file=sys.stderr,
        )

    # 永远 0:flaky 本来就允许 pass,治理走 issue / runbook 而非阻断
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
