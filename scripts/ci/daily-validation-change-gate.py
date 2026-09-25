#!/usr/bin/env python3
"""仅在北京时间当天存在代码或配置变更时运行 nightly 仿真验证。"""

from __future__ import annotations

import os
import subprocess
import sys
from datetime import date, datetime, time, timezone
from pathlib import Path
from zoneinfo import ZoneInfo


ROOT = Path(__file__).resolve().parents[2]
SHANGHAI = ZoneInfo("Asia/Shanghai")


def output(key: str, value: str) -> None:
    github_output = os.environ.get("GITHUB_OUTPUT")
    if github_output:
        with open(github_output, "a", encoding="utf-8") as stream:
            stream.write(f"{key}={value}\n")


def report_gate_result(status: str, code: str, detail: str = "0") -> None:
    subprocess.run(
        [
            "bash",
            "-c",
            'source "$1"; gate_result "$2" "$3" "$4" "$5"',
            "gate-result",
            str(ROOT / "scripts/lib/gate-result.sh"),
            status,
            code,
            "每日仿真与严格验证变更检测",
            detail,
        ],
        check=True,
    )


def main() -> int:
    today_text = os.environ.get("VALIDATION_DAY")
    today = date.fromisoformat(today_text) if today_text else datetime.now(SHANGHAI).date()
    start = datetime.combine(today, time.min, SHANGHAI).astimezone(timezone.utc)
    since = start.isoformat(timespec="seconds")
    result = subprocess.run(
        ["git", "log", f"--since={since}", "--format=", "--name-only", "--no-renames", "HEAD"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    changed_paths = sorted({line.strip() for line in result.stdout.splitlines() if line.strip()})
    code_paths = [
        path
        for path in changed_paths
        if Path(path).suffix.lower() not in {".md", ".rst"} and path not in {"LICENSE", "NOTICE"}
    ]
    force = os.environ.get("FORCE_VALIDATION", "false").lower() == "true"
    should_run = force or bool(code_paths)
    reason = (
        "手动触发并强制运行"
        if force
        else (f"当天检测到 {len(code_paths)} 个代码/配置变更路径" if code_paths else "当天无代码/配置变更")
    )

    output("should_run", "true" if should_run else "false")
    output("reason", reason)
    print(f"北京时间日期：{today.isoformat()}；检查起点：{since}")
    print(f"判定依据：{reason}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(
                f"## 每日仿真与严格验证\n\n- 北京日期：`{today.isoformat()}`\n"
                f"- 检查起点：`{since}`\n- 判定依据：{reason}\n"
            )

    if force:
        report_gate_result("PASS", "DAILY_SIM_STRICT_MANUAL_FORCE")
    elif code_paths:
        report_gate_result("PASS", "DAILY_SIM_STRICT_CODE_CHANGED")
    else:
        report_gate_result("SKIP", "DAILY_SIM_STRICT_NO_CODE_CHANGE", reason)

    if code_paths:
        print("变更路径（最多显示 30 项）：")
        for path in code_paths[:30]:
            print(f"  {path}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, subprocess.CalledProcessError, ValueError) as exc:
        try:
            report_gate_result("FAIL", "DAILY_SIM_STRICT_CHANGE_DETECTION", "2")
        except (OSError, subprocess.CalledProcessError):
            print(
                "❌ 不通过 | code=DAILY_SIM_STRICT_CHANGE_DETECTION | "
                "gate=每日仿真与严格验证变更检测 | exit_code=2",
                file=sys.stderr,
            )
        print(f"检测错误详情：{exc}", file=sys.stderr)
        raise SystemExit(2)
