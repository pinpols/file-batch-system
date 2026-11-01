#!/usr/bin/env python3
# 用途：按最近一次成功验证的提交检测代码或配置变更。
# 用法：在仓库根目录执行 python3 scripts/ci/daily-validation-change-gate.py；参数与前置环境见下方说明及所属 README。
"""按最近一次成功验证的提交检测代码或配置变更。"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import date, datetime, time, timedelta, timezone
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
    gate_environment = os.environ.copy()
    gate_environment.setdefault("BATCH_GATE_GROUP", "scheduled-validation")
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
        env=gate_environment,
    )


def validation_day(now: datetime, event_name: str, schedule: str) -> date:
    if event_name != "schedule" or not schedule:
        return now.date()

    fields = schedule.split()
    if len(fields) != 5 or fields[2:] != ["*", "*", "*"]:
        raise ValueError(f"Unsupported daily validation schedule: {schedule}")
    scheduled_utc = datetime.combine(
        now.date(), time(hour=int(fields[1]), minute=int(fields[0]), tzinfo=timezone.utc)
    )
    scheduled_local = scheduled_utc.astimezone(SHANGHAI)
    if now < scheduled_local:
        scheduled_local -= timedelta(days=1)
    return scheduled_local.date()


def relevant_paths(paths: list[str]) -> list[str]:
    return sorted(
        {path for path in paths if Path(path).suffix.lower() not in {".md", ".rst"}
         and path not in {"LICENSE", "NOTICE"}}
    )


def last_validated_sha(runs: list[dict]) -> str | None:
    for run in runs:
        for job in run["jobs"]:
            if job["name"] in {"sim-and-strict", "Run simulation and strict validation"}:
                result = job["conclusion"]
                if result == "success":
                    return run["head_sha"]
                if result in {"failure", "timed_out", "cancelled", "action_required"}:
                    return None
    return None


def gh_json(endpoint: str) -> dict:
    result = subprocess.run(
        ["gh", "api", endpoint], cwd=ROOT, check=True, capture_output=True, text=True
    )
    return json.loads(result.stdout)


def successful_baseline() -> str | None:
    repo = os.environ["GITHUB_REPOSITORY"]
    endpoint = (f"repos/{repo}/actions/workflows/daily-sim-strict-validation.yml/runs"
                "?branch=main&status=completed&per_page=100")
    for run in gh_json(endpoint)["workflow_runs"]:
        jobs = gh_json(f"repos/{repo}/actions/runs/{run['id']}/jobs?per_page=100")["jobs"]
        candidate = {"head_sha": run["head_sha"], "jobs": jobs}
        simulation = [
            job for job in jobs
            if job["name"] in {"sim-and-strict", "Run simulation and strict validation"}
        ]
        if simulation and simulation[0]["conclusion"] != "skipped":
            return last_validated_sha([candidate])
        if run["conclusion"] in {"failure", "timed_out", "cancelled", "action_required"}:
            return None
    return None


def main() -> int:
    today_text = os.environ.get("VALIDATION_DAY")
    now_text = os.environ.get("VALIDATION_NOW")
    now = datetime.fromisoformat(now_text) if now_text else datetime.now(SHANGHAI)
    if now.tzinfo is None:
        raise ValueError("VALIDATION_NOW must include a timezone offset")
    now = now.astimezone(SHANGHAI)
    today = (
        date.fromisoformat(today_text)
        if today_text
        else validation_day(
            now,
            os.environ.get("VALIDATION_EVENT", ""),
            os.environ.get("VALIDATION_SCHEDULE", ""),
        )
    )
    force = os.environ.get("FORCE_VALIDATION", "false").lower() == "true"
    baseline = None if force else successful_baseline()
    code_paths: list[str] = []
    if baseline:
        ancestor = subprocess.run(
            ["git", "merge-base", "--is-ancestor", baseline, "HEAD"], cwd=ROOT, check=False
        )
        if ancestor.returncode == 0:
            result = subprocess.run(
                ["git", "diff", "--name-only", "--no-renames", baseline, "HEAD"],
                cwd=ROOT, check=True, capture_output=True, text=True,
            )
            code_paths = relevant_paths(result.stdout.splitlines())
        elif ancestor.returncode != 1:
            raise subprocess.CalledProcessError(ancestor.returncode, ancestor.args)
        else:
            baseline = None
    should_run = force or baseline is None or bool(code_paths)
    if force:
        reason = "手动强制运行"
    elif baseline is None:
        reason = "无可用成功验证基线，执行验证"
    elif code_paths:
        reason = f"自成功基线 {baseline} 有 {len(code_paths)} 个代码/配置变更"
    else:
        reason = f"自成功基线 {baseline} 无代码/配置变更"
    output("should_run", "true" if should_run else "false")
    output("reason", reason)
    print(f"验证日期（北京时间）：{today.isoformat()}；判定依据：{reason}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(
                f"## 每日仿真与严格验证\n\n- 计划验证日：`{today.isoformat()}`\n"
                f"- 判定依据：{reason}\n"
            )

    if force:
        report_gate_result("PASS", "DAILY_SIM_STRICT_MANUAL_FORCE")
    elif baseline is None:
        report_gate_result("PASS", "DAILY_SIM_STRICT_RETRY_REQUIRED")
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
    except (OSError, subprocess.CalledProcessError, ValueError, KeyError) as exc:
        try:
            report_gate_result("FAIL", "DAILY_SIM_STRICT_CHANGE_DETECTION", "2")
        except (OSError, subprocess.CalledProcessError):
            print(
                "❌ 不通过 | code=DAILY_SIM_STRICT_CHANGE_DETECTION | "
                "gate=每日仿真与严格验证变更检测 | group=scheduled-validation | "
                "mode=enforce | exit_code=2 | action=fix_and_retry",
                file=sys.stderr,
            )
        print(f"检测错误详情：{exc}", file=sys.stderr)
        raise SystemExit(2)
