#!/usr/bin/env python3
"""
检测 application.yml 和 docker-compose*.yml 里同一环境变量的 default 是否同步。

背景：曾发生 read-replica 在 application.yml fallback=true 但
docker/compose/app.yml 默认 :-false 的 bug，导致行为静默不一致。
本脚本作为 CI 兜底，确保两边 default 永远对齐。

匹配规则：
  application.yml: ${VAR:default}    (Spring Boot 占位符)
  compose.yml:     ${VAR:-default}   (POSIX shell 风格)

只检查"VAR 同时出现在两边且都带 default"的条目；
- 仅 yml 有（未透传到 compose）→ 跳过（compose 不消费就无歧义）
- 仅 compose 有（如 JAVA_OPTS / IMAGE_TAG）→ 跳过（无 yml 对应方）
- VAR 用 :?required 形式 → 跳过（明确要求必传，无 default）
- 嵌套占位符 ${VAR:-${OTHER}} → 跳过（间接默认，复杂场景人工 review）

CI 用法：
  python3 scripts/ci/check-config-defaults-sync.py        生成报告
  python3 scripts/ci/check-config-defaults-sync.py --check 不一致 → exit 1
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent.parent

# Spring Boot ${VAR:default} —— 冒号后非花括号、非 ?
SPRING_PLACEHOLDER = re.compile(r'\$\{([A-Z][A-Z0-9_]*):([^${}?][^${}]*)\}')

# Compose ${VAR:-default} or ${VAR:?msg}
COMPOSE_PLACEHOLDER = re.compile(r'\$\{([A-Z][A-Z0-9_]*):-([^${}][^${}]*)\}')

# URL / 连接串前缀
URL_PREFIXES = ("http://", "https://", "jdbc:")
# host:port 格式（如 kafka:9092 / localhost:19092）
HOST_PORT = re.compile(r'^[a-z][a-z0-9.-]*:\d+$')


def is_network_topology_pair(yml_v: str, compose_v: str) -> bool:
    """判断 yml/compose 之间的差异是否为"IDE vs 容器网络"拓扑差异（预期，by-design）。

    规则：
    - 两边都是 URL（http/https/jdbc）→ 拓扑差异
    - 两边都是 host:port → 拓扑差异
    - 恰好一侧含 "localhost" 而另一侧不含 → 拓扑差异（裸 hostname 场景，如
      BATCH_REDIS_HOST: localhost vs redis）
    """
    if yml_v.startswith(URL_PREFIXES) and compose_v.startswith(URL_PREFIXES):
        return True
    if HOST_PORT.match(yml_v) and HOST_PORT.match(compose_v):
        return True
    yml_local = "localhost" == yml_v or "localhost" in yml_v.split(":")[0]
    compose_local = "localhost" == compose_v or "localhost" in compose_v.split(":")[0]
    return yml_local != compose_local


# 已知 by-design 差异的白名单（VAR → 原因）。其他差异一律视为 bug。
DRIFT_ALLOWLIST = {
    "BATCH_CONSOLE_INSTANCE_ID":
        "compose 加 -local 后缀区分多容器实例；yml 默认裸名给 IDE 直跑",
}


def scan_yml(path: Path, pattern: re.Pattern) -> dict[str, set[str]]:
    """返回 {VAR: {default1, default2, ...}}（同 VAR 多处声明都收集）。"""
    if not path.exists():
        return {}
    content = path.read_text(encoding="utf-8")
    out: dict[str, set[str]] = defaultdict(set)
    for m in pattern.finditer(content):
        var, default = m.group(1), m.group(2).strip()
        out[var].add(default)
    return out


def merge(*sources: dict[str, set[str]]) -> dict[str, set[str]]:
    out: dict[str, set[str]] = defaultdict(set)
    for s in sources:
        for k, v in s.items():
            out[k].update(v)
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="CI 模式：mismatch → exit 1")
    args = parser.parse_args()

    # 收集所有 application.yml（生产 + local；不扫 test/e2e，它们故意覆盖）
    yml_files = [
        ROOT / "batch-common/src/main/resources/application.yml",
        ROOT / "batch-common/src/main/resources/batch-defaults.yml",
        ROOT / "batch-orchestrator/src/main/resources/application.yml",
        ROOT / "batch-trigger/src/main/resources/application.yml",
        ROOT / "batch-console-api/src/main/resources/application.yml",
        ROOT / "batch-worker-core/src/main/resources/application.yml",
        ROOT / "batch-worker-import/src/main/resources/application.yml",
        ROOT / "batch-worker-export/src/main/resources/application.yml",
        ROOT / "batch-worker-dispatch/src/main/resources/application.yml",
        ROOT / "batch-worker-atomic/src/main/resources/application.yml",
    ]
    yml_defaults = merge(*(scan_yml(p, SPRING_PLACEHOLDER) for p in yml_files))

    # 扫所有 docker-compose 文件
    compose_files = [
        ROOT / "docker-compose.yml",
        ROOT / "docker/compose/app.yml",
        ROOT / "docker/compose/observability.yml",
        ROOT / "docker/compose/test.yml",
    ]
    compose_defaults = merge(*(scan_yml(p, COMPOSE_PLACEHOLDER) for p in compose_files))

    common = set(yml_defaults) & set(compose_defaults)

    mismatches: list[tuple[str, str, str]] = []
    yml_internal_dups: list[tuple[str, set[str]]] = []
    network_topology_drifts: list[tuple[str, str, str]] = []
    allowlisted_drifts: list[tuple[str, str, str, str]] = []

    for var in sorted(common):
        yml_vals = yml_defaults[var]
        compose_vals = compose_defaults[var]

        # yml 内部不一致（同 key 多模块声明不同 default）
        if len(yml_vals) > 1:
            yml_internal_dups.append((var, yml_vals))
            continue
        # compose 内部不一致
        if len(compose_vals) > 1:
            mismatches.append((var, "[compose 内部多个不同 default]", " / ".join(sorted(compose_vals))))
            continue

        yml_v = next(iter(yml_vals))
        compose_v = next(iter(compose_vals))
        if yml_v == compose_v:
            continue

        # 分类不同步原因
        if is_network_topology_pair(yml_v, compose_v):
            network_topology_drifts.append((var, yml_v, compose_v))
        elif var in DRIFT_ALLOWLIST:
            allowlisted_drifts.append((var, yml_v, compose_v, DRIFT_ALLOWLIST[var]))
        else:
            mismatches.append((var, yml_v, compose_v))

    yml_only = sorted(set(yml_defaults) - set(compose_defaults))
    compose_only = sorted(set(compose_defaults) - set(yml_defaults))

    print(f"扫描完成：")
    print(f"  yml ({len(yml_files)} 文件)         共 {len(yml_defaults)} 个带默认值的占位符")
    print(f"  compose ({len(compose_files)} 文件) 共 {len(compose_defaults)} 个带默认值的占位符")
    print(f"  共同 VAR                            {len(common)} 个")
    print(f"  仅 yml                              {len(yml_only)} 个 (compose 不透传，OK)")
    print(f"  仅 compose                          {len(compose_only)} 个 (无 yml 消费方，OK)")
    print()

    if network_topology_drifts:
        print(f"🌐 网络拓扑差异（预期，by-design）{len(network_topology_drifts)} 项：")
        for var, yml_v, compose_v in network_topology_drifts:
            print(f"  {var}")
            print(f"    yml:     {yml_v}")
            print(f"    compose: {compose_v}")
        print()

    if allowlisted_drifts:
        print(f"📋 白名单允许的差异 {len(allowlisted_drifts)} 项（DRIFT_ALLOWLIST）：")
        for var, yml_v, compose_v, reason in allowlisted_drifts:
            print(f"  {var}: yml={yml_v} / compose={compose_v}")
            print(f"    原因: {reason}")
        print()

    if yml_internal_dups:
        print(f"⚠️  yml 内部 default 不一致（同一 VAR 在多个模块写了不同 default）{len(yml_internal_dups)} 项：")
        for var, vals in yml_internal_dups:
            print(f"  {var}: {sorted(vals)}")
        print()

    if mismatches:
        print(f"❌ yml ↔ compose 业务参数 default 不同步 {len(mismatches)} 项：")
        print(f"  {'VAR':<55} {'yml':<25} {'compose':<25}")
        print(f"  {'-'*55} {'-'*25} {'-'*25}")
        for var, yml_v, compose_v in mismatches:
            print(f"  {var:<55} {yml_v:<25} {compose_v:<25}")
        print()
        print("修复方法：让 application.yml 的 ${VAR:default} 和 docker-compose 的")
        print(" ${VAR:-default} 同步；通常以 application.yml 为准（业务语义层）。")
        print()
        print("如果差异是 by-design（如网络拓扑）：URL/host:port 已自动跳过；")
        print("其他特殊场景在脚本顶部 DRIFT_ALLOWLIST 加白名单 + 注明原因。")
        return 1 if args.check else 0

    print("✅ 全部业务参数同步（网络拓扑 / 白名单差异已识别）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
