#!/usr/bin/env python3
"""
校验 Helm 模板里注入的 BATCH_* 环境变量不会漂移成应用不消费的名字。

背景：曾出现 Helm ConfigMap 注入
`BATCH_RATE_LIMIT_MAX_NEW_PER_TENANT_PER_MINUTE`，但 Spring 实际读取的是
`BATCH_RATE_LIMIT_MAX_NEW_REQUESTS_PER_TENANT_PER_MINUTE`。这种错名不会影响
本地 compose，也不会被 application.yml 默认值同步脚本发现，生产 values 覆盖会静默失效。

本脚本只做轻量静态检查：
- Helm templates 中声明的 BATCH_* 变量，必须出现在 application.yml 占位符中，或在
  明确白名单内（Spring relaxed binding 直接绑定到 @ConfigurationProperties / entrypoint
  消费 / 端口与运维变量）。
- 生产安全/限流类一等开关必须在 Helm templates 中显式注入。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from feature_switch_registry import helm_required_env_vars

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent.parent

APP_YML_FILES = [
    ROOT / "batch-common/src/main/resources/application.yml",
    ROOT / "batch-common/src/main/resources/application-prod.yml",
    ROOT / "batch-common/src/main/resources/batch-defaults.yml",
    ROOT / "batch-orchestrator/src/main/resources/application.yml",
    ROOT / "batch-trigger/src/main/resources/application.yml",
    ROOT / "batch-console-api/src/main/resources/application.yml",
    ROOT / "batch-worker/core/src/main/resources/application.yml",
    ROOT / "batch-worker/import/src/main/resources/application.yml",
    ROOT / "batch-worker/export/src/main/resources/application.yml",
    ROOT / "batch-worker/process/src/main/resources/application.yml",
    ROOT / "batch-worker/dispatch/src/main/resources/application.yml",
    ROOT / "batch-worker/atomic/src/main/resources/application.yml",
]

HELM_TEMPLATE_DIR = ROOT / "helm/batch-platform/templates"
SPRING_PLACEHOLDER = re.compile(r"\$\{(BATCH_[A-Z0-9_]+):")
TEMPLATE_ENV_NAME = re.compile(r"^\s*(?:-\s+name:\s+)?(BATCH_[A-Z0-9_]+)\s*:", re.MULTILINE)

# 这些变量不一定出现在 yml 占位符中，但确实由 Spring relaxed binding、
# entrypoint、K8s 探针/端口或安全模板直接消费。
KNOWN_HELM_ONLY_VARS = {
    "BATCH_CONSOLE_API_PORT",
    "BATCH_CONSOLE_JWT_SECRET",
    "BATCH_CONSOLE_PRIMARY_POOL",
    "BATCH_CONSOLE_REALTIME_REPLAY_MAX_ENTRIES",
    "BATCH_CONSOLE_REALTIME_REPLAY_TTL",
    "BATCH_CONSOLE_REPLICA_POOL",
    "BATCH_CONSOLE_SECURITY_LOGIN_ENCRYPTION_ENABLED",
    "BATCH_CONSOLE_SECURITY_LOGIN_ENCRYPTION_PRIVATE_KEY_PEM",
    "BATCH_CONSOLE_SECURITY_LOGIN_ENCRYPTION_PUBLIC_KEY_PEM",
    "BATCH_CONSOLE_SECURITY_LOGIN_ENCRYPTION_REQUIRED",
    "BATCH_CONSOLE_SECURITY_RATE_LIMIT_EXPENSIVE_OP_USER_LIMIT_PER_MINUTE",
    "BATCH_CONSOLE_SECURITY_RATE_LIMIT_FILE_OP_USER_LIMIT_PER_MINUTE",
    "BATCH_DATASOURCE_BUSINESS_ROUTING_ENABLED",
    "BATCH_DATASOURCE_BUSINESS_ROUTING_PLACEMENT_SOURCE",
    "BATCH_LOCALE",
    "BATCH_MANAGEMENT_PORT",
    "BATCH_ORCHESTRATOR_PLATFORM_DB_MAX_POOL_SIZE",
    "BATCH_ORCHESTRATOR_PORT",
    "BATCH_OUTBOX_SHARDING_MODE",
    "BATCH_OUTBOX_SHARD_TOTAL",
    "BATCH_REQUEST_SIGNING_ENABLED",
    "BATCH_SENSOR_ENABLED",
    "BATCH_TRIGGER_PORT",
    "BATCH_WORKER_ATOMIC_ISOLATION_ACKNOWLEDGED",
    "BATCH_WORKER_ATOMIC_REQUIRE_ISOLATION",
    "BATCH_WORKER_CHECKPOINT_ENABLED",
    "BATCH_WORKER_CHECKPOINT_STAGE_SKIP_ENABLED",
    "BATCH_WORKER_DISPATCH_PORT",
    "BATCH_WORKER_EXPORT_PORT",
    "BATCH_WORKER_IMPORT_PORT",
    "BATCH_WORKER_PROCESS_PORT",
}

# 运维手册登记、且 Helm 生产部署应能一等配置的开关入口。
REQUIRED_HELM_FEATURE_VARS = helm_required_env_vars()


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def main() -> int:
    app_vars: set[str] = set()
    for path in APP_YML_FILES:
        app_vars.update(SPRING_PLACEHOLDER.findall(read(path)))

    helm_vars: set[str] = set()
    for path in sorted(HELM_TEMPLATE_DIR.glob("*.yaml")):
        helm_vars.update(TEMPLATE_ENV_NAME.findall(read(path)))

    allowed = app_vars | KNOWN_HELM_ONLY_VARS
    unknown = sorted(helm_vars - allowed)
    missing_required = sorted(REQUIRED_HELM_FEATURE_VARS - helm_vars)

    print("Helm env sync scan:")
    print(f"  app yml BATCH vars: {len(app_vars)}")
    print(f"  helm template BATCH vars: {len(helm_vars)}")

    if unknown:
        print()
        print("❌ Helm 注入了应用未识别的 BATCH_* 变量：")
        for var in unknown:
            print(f"  {var}")
        print("请修正变量名，或确认其由 relaxed binding / entrypoint 消费后加入白名单。")

    if missing_required:
        print()
        print("❌ Helm 缺少生产一等开关入口：")
        for var in missing_required:
            print(f"  {var}")

    if unknown or missing_required:
        return 1

    print("✅ Helm BATCH_* env 与应用消费入口一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
