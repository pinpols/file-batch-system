#!/usr/bin/env python3
"""
守护:dual-use(RCE 级)Task SPI executor 只允许在白名单 worker 开启。

背景：Task SPI 的 shell / stored-proc executor 能执行任意命令 / 存储过程(RCE 级
dual-use)。代码在共享的 batch-worker-core,四个 worker app 技术上都能 @ConditionalOnProperty
开启。策略 A(2026-05-30 定):这两类只允许 batch-worker-process 开;其余 worker 误开 → CI 红。
sql / http 低危,不纳入本守护。

判定信号：worker 的 application*.yml 里 batch.worker.executors.{shell,stored-proc}.enabled
解析为 true(字面 true,或占位符 ${ENV:true} 默认 true)即视为"开启"。
占位符默认 false(${ENV:false})不算开启(部署时显式传 ENV=true 才开,那是 deploy-time 决策)。

CI 用法：
  python3 scripts/ci/check-dual-use-executor-allowlist.py          报告
  python3 scripts/ci/check-dual-use-executor-allowlist.py --check  违规 → exit 1
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

import yaml

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent.parent

WORKERS = [
    "batch-worker-import",
    "batch-worker-export",
    "batch-worker-process",
    "batch-worker-dispatch",
]

# 守护的 dual-use(RCE 级)executor → 允许开启的 worker 集合(策略 A：只 process)。
ALLOWLIST = {
    "shell": {"batch-worker-process"},
    "stored-proc": {"batch-worker-process"},
}

# ${ENV:default} —— 取冒号后默认值
PLACEHOLDER = re.compile(r"^\$\{[A-Za-z_][A-Za-z0-9_]*:(.*)\}$")


def resolve_bool(value) -> bool:
    """yml 值解析成 bool。字面 true/false 直读;占位符取默认值;其它视为 false。"""
    if isinstance(value, bool):
        return value
    if isinstance(value, str):
        m = PLACEHOLDER.match(value.strip())
        if m:
            return m.group(1).strip().lower() == "true"
        return value.strip().lower() == "true"
    return False


def executor_enabled(doc: dict, executor: str) -> bool:
    """从 yml 文档里取 batch.worker.executors.<executor>.enabled 的布尔。"""
    node = doc
    for seg in ("batch", "worker", "executors", executor):
        if not isinstance(node, dict):
            return False
        node = node.get(seg)
        if node is None:
            return False
    if not isinstance(node, dict):
        return False
    return resolve_bool(node.get("enabled"))


def scan_worker(worker: str) -> set[str]:
    """返回该 worker 开启的 dual-use executor 集合。"""
    enabled = set()
    res_dir = ROOT / worker / "src" / "main" / "resources"
    for yml in sorted(res_dir.glob("application*.yml")):
        try:
            docs = list(yaml.safe_load_all(yml.read_text(encoding="utf-8")))
        except yaml.YAMLError:
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            for executor in ALLOWLIST:
                if executor_enabled(doc, executor):
                    enabled.add(executor)
    return enabled


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="违规则 exit 1")
    args = parser.parse_args()

    violations = []
    report = []
    for worker in WORKERS:
        enabled = scan_worker(worker)
        report.append(f"{worker}: dual-use enabled = {sorted(enabled) or '[]'}")
        for executor in enabled:
            allowed = ALLOWLIST.get(executor, set())
            if worker not in allowed:
                violations.append(
                    f"  ✗ {worker} 开启了 dual-use executor '{executor}',"
                    f"但策略只允许 {sorted(allowed)}"
                )

    print("== dual-use executor allowlist 扫描 ==")
    for line in report:
        print("  " + line)

    if violations:
        print("\n违规(RCE 级 executor 在非白名单 worker 开启):")
        print("\n".join(violations))
        print(
            "\n修复:要么在对应 worker 关掉该 executor,"
            "要么显式修改 scripts/ci/check-dual-use-executor-allowlist.py 的 ALLOWLIST"
            "(= 扩 RCE 信任边界,需 review)。"
        )
        return 1 if args.check else 0

    print("\nOK: 所有 dual-use executor 都在白名单 worker 内(或全未开启)。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
