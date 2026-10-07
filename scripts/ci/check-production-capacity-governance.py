#!/usr/bin/env python3
"""校验生产容量治理自动化入口、只读 SQL 和 runbook 索引一致。"""

from __future__ import annotations

import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "PRODUCTION_CAPACITY_GOVERNANCE"
GATE_NAME = "生产容量治理自动化"

INSPECT_ALL = ROOT / "scripts/ops/inspect-all.sh"
SCRIPT = ROOT / "scripts/ops/inspect-production-capacity.sh"
RETENTION_PLAN_SCRIPT = ROOT / "scripts/ops/plan-production-retention.sh"
RUNTIME_GOVERNANCE_SCRIPT = ROOT / "scripts/ops/inspect-runtime-governance.sh"
TOOLBOX_SCRIPT = ROOT / "scripts/ops/run-toolbox.sh"
SQL = ROOT / "scripts/ops/sql/inspect-production-capacity-postgres.sql"
RETENTION_PLAN_SQL = ROOT / "scripts/ops/sql/plan-production-retention-postgres.sql"
RUNBOOK = ROOT / "docs/runbook/production-capacity-governance.md"
RUNBOOK_INDEX = ROOT / "docs/runbook/README.md"
OPS_INDEX = ROOT / "scripts/ops/README.md"
CI_INDEX = ROOT / "scripts/ci/README.md"
PR_GATE = ROOT / ".github/workflows/pr-gate.yml"
FULL_GATE = ROOT / ".github/workflows/full-ci-gate.yml"
COMPOSE = ROOT / "docker-compose.yml"
ENV_EXAMPLE = ROOT / ".env.example"
TOOLBOX_DOCKERFILE = ROOT / "deploy/docker/Dockerfile.ops-toolbox"

WRITE_SQL_RE = re.compile(
    r"^\s*(INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|VACUUM|REINDEX|CLUSTER)\b",
    re.IGNORECASE,
)


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def read(path: Path, errors: list[str]) -> str:
    if not path.is_file():
        errors.append(f"缺少文件: {rel(path)}")
        return ""
    return path.read_text(encoding="utf-8")


def require_contains(label: str, text: str, needles: list[str], errors: list[str]) -> None:
    missing = [needle for needle in needles if needle not in text]
    if missing:
        errors.append(f"{label} 缺少内容: {', '.join(missing)}")


def require_matches(label: str, text: str, pattern: str, description: str, errors: list[str]) -> None:
    if re.search(pattern, text, re.MULTILINE) is None:
        errors.append(f"{label} 缺少内容: {description}")


def main() -> int:
    errors: list[str] = []

    inspect_all = read(INSPECT_ALL, errors)
    script = read(SCRIPT, errors)
    retention_plan_script = read(RETENTION_PLAN_SCRIPT, errors)
    runtime_governance_script = read(RUNTIME_GOVERNANCE_SCRIPT, errors)
    toolbox_script = read(TOOLBOX_SCRIPT, errors)
    sql = read(SQL, errors)
    retention_plan_sql = read(RETENTION_PLAN_SQL, errors)
    runbook = read(RUNBOOK, errors)
    runbook_index = read(RUNBOOK_INDEX, errors)
    ops_index = read(OPS_INDEX, errors)
    ci_index = read(CI_INDEX, errors)
    pr_gate = read(PR_GATE, errors)
    full_gate = read(FULL_GATE, errors)
    compose = read(COMPOSE, errors)
    env_example = read(ENV_EXAMPLE, errors)
    toolbox_dockerfile = read(TOOLBOX_DOCKERFILE, errors)

    require_contains(
        rel(INSPECT_ALL),
        inspect_all,
        [
            "inspect-production-capacity.sh",
            "plan-production-retention.sh",
            "inspect-runtime-governance.sh",
            "BATCH_INSPECT_SKIP_PRODUCTION_CAPACITY",
            "BATCH_INSPECT_SKIP_PRODUCTION_RETENTION_PLAN",
            "BATCH_INSPECT_SKIP_RUNTIME_GOVERNANCE",
            "inspect-production-capacity",
            "plan-production-retention",
            "inspect-runtime-governance",
        ],
        errors,
    )
    require_contains(
        rel(SCRIPT),
        script,
        [
            "BATCH_PROD_CAPACITY_STRICT",
            "BATCH_PROD_CAPACITY_POSTGRES_STRICT",
            "BATCH_PROD_CAPACITY_KAFKA_STRICT",
            "BATCH_PROD_CAPACITY_OBJECT_STORE_STRICT",
            "BATCH_PROD_CAPACITY_KAFKA_REQUIRE_RETENTION",
            "BATCH_PROD_CAPACITY_OBJECT_STORE_REQUIRE_LIFECYCLE",
            "inspect-production-capacity-postgres.sql",
            "Kafka capacity",
            "Object storage capacity",
        ],
        errors,
    )
    if "DELETE" in script or "kafka-consumer-groups.sh --reset-offsets" in script:
        errors.append(f"{rel(SCRIPT)} 不得包含生产清理或 offset 重置动作")
    require_contains(
        rel(RETENTION_PLAN_SCRIPT),
        retention_plan_script,
        [
            "BATCH_PROD_RETENTION_STRICT",
            "BATCH_PROD_RETENTION_POSTGRES_STRICT",
            "BATCH_PROD_RETENTION_KAFKA_STRICT",
            "BATCH_PROD_RETENTION_OBJECT_STORE_STRICT",
            "BATCH_PROD_RETENTION_REDIS_STRICT",
            "BATCH_PROD_RETENTION_KAFKA_REQUIRE_TOPIC_RETENTION",
            "BATCH_PROD_RETENTION_OBJECT_STORE_REQUIRE_LIFECYCLE",
            "BATCH_PROD_RETENTION_REDIS_PATTERNS",
            "plan-production-retention-postgres.sql",
            "Kafka retention plan",
            "Object storage lifecycle plan",
            "Redis retention plan",
            "TTL",
            "redis-cli",
        ],
        errors,
    )
    if "kafka-consumer-groups.sh --reset-offsets" in retention_plan_script:
        errors.append(f"{rel(RETENTION_PLAN_SCRIPT)} 不得包含生产 offset 重置动作")
    require_contains(
        rel(RUNTIME_GOVERNANCE_SCRIPT),
        runtime_governance_script,
        [
            "BATCH_DISPATCH_CHANNEL_TYPES_GOVERNED",
            "LOCAL NAS SFTP OSS API API_PUSH EMAIL",
            "BATCH_DISPATCH_SFTP_STRICT_HOST_KEY_REQUIRED",
            "BATCH_DISPATCH_API_EGRESS_ALLOWLIST_REQUIRED",
            "BATCH_DISPATCH_EMAIL_TLS_REQUIRED",
            "BATCH_WORKER_REPORT_OUTBOX_STORAGE",
            "BATCH_QUOTA_REDIS_FAILURE_MODE",
            "BATCH_SHEDLOCK_PROVIDER",
            "BATCH_CONSOLE_READ_REPLICA_ENABLED",
            "BATCH_DATASOURCE_BUSINESS_ROUTING_ENABLED",
            "BATCH_TRIGGER_MISFIRE_PENDING_RETENTION_DAYS",
            "BATCH_TRIGGER_QUARTZ_DB_MAX_POOL_SIZE",
            "BATCH_OBSERVABILITY_RETENTION_DAYS",
            "BATCH_OPENLINEAGE_ENABLED",
            "BATCH_EXTERNAL_ENDPOINT_EGRESS_ALLOWLIST_REQUIRED",
            "--profile-file",
            "Runtime governance: PASSED",
        ],
        errors,
    )

    require_contains(
        rel(TOOLBOX_SCRIPT),
        toolbox_script,
        [
            "--profile ops",
            "ops-toolbox",
            "docker compose",
        ],
        errors,
    )
    require_contains(
        rel(TOOLBOX_DOCKERFILE),
        toolbox_dockerfile,
        [
            "postgresql-client-${POSTGRES_CLIENT_MAJOR}",
            "COPY --from=kafka-client /opt/kafka/libs /opt/kafka-client/libs",
            "exec /opt/kafka-client/bin/%s",
            "kafka-topics.sh --version",
            "/opt/bitnami/minio-client/bin/mc",
            "redis-tools",
            "PATH=/opt/kafka-client/bin",
            "USER batch:batch",
        ],
        errors,
    )
    require_matches(
        rel(TOOLBOX_DOCKERFILE),
        toolbox_dockerfile,
        r"^FROM apache/kafka:\$\{KAFKA_IMAGE_TAG\}@sha256:[0-9a-f]{64} AS kafka-client$",
        "Kafka 客户端基础镜像必须同时固定 tag 与 sha256 digest",
        errors,
    )
    require_contains(
        rel(COMPOSE),
        compose,
        [
            "ops-toolbox:",
            'profiles: ["ops"]',
            "Dockerfile.ops-toolbox",
            "BATCH_PROD_CAPACITY_KAFKA_BIN_DIR: /opt/kafka-client/bin",
        ],
        errors,
    )
    require_contains(
        rel(ENV_EXAMPLE),
        env_example,
        [
            "OPS_TOOLBOX_PYTHON_VERSION",
            "OPS_TOOLBOX_POSTGRES_CLIENT_MAJOR",
            "KAFKA_IMAGE_TAG",
            "BATCH_PROD_RETENTION_OLD_RUNTIME_DAYS",
            "BATCH_PROD_RETENTION_KAFKA_REQUIRE_TOPIC_RETENTION",
            "BATCH_PROD_RETENTION_OBJECT_STORE_REQUIRE_LIFECYCLE",
            "BATCH_PROD_RETENTION_REDIS_PATTERNS",
        ],
        errors,
    )

    for sql_path, sql_text in ((SQL, sql), (RETENTION_PLAN_SQL, retention_plan_sql)):
        for line_number, line in enumerate(sql_text.splitlines(), 1):
            if line.lstrip().startswith(("--", "\\echo")):
                continue
            if WRITE_SQL_RE.search(line):
                errors.append(f"{rel(sql_path)}:{line_number} 应保持只读，不得包含写入、DDL 或维护命令")

    require_contains(
        rel(RUNBOOK),
        runbook,
        [
            "inspect-production-capacity.sh",
            "plan-production-retention.sh",
            "只读巡检",
            "保留治理计划入口",
            "不提供一键删除生产数据",
            "PostgreSQL",
            "Kafka",
            "对象存储",
            "分域参数",
            "运维工具箱",
            "生产账号与权限边界",
            "最小权限账号",
            "只读账号",
            "不进入应用发布镜像",
            "非 root",
            "10001:10001",
            "inspect-runtime-governance.sh",
            "NAS / SFTP / API / API_PUSH /",
            "Worker Report Outbox",
            "Quartz",
            "OpenLineage",
            "不绑定本仓库的 Docker Compose",
            "--profile-file /etc/batch/prod-governance.env",
            "托管服务参数组",
            "平台已有的 bastion",
            "SUPERUSER",
            "root access key",
            "FLUSH*",
            "TTL",
            "压测",
            "上线准入清单",
        ],
        errors,
    )
    require_contains(
        rel(RUNBOOK_INDEX),
        runbook_index,
        ["production-capacity-governance.md", "生产容量与存储增长治理", "容量评估 / 上量"],
        errors,
    )
    require_contains(
        rel(OPS_INDEX),
        ops_index,
        [
            "inspect-production-capacity.sh",
            "plan-production-retention.sh",
            "inspect-runtime-governance.sh",
            "run-toolbox.sh",
            "最小权限账号",
            "非 root",
            "production-capacity-governance.md",
        ],
        errors,
    )
    require_contains(
        rel(CI_INDEX),
        ci_index,
        ["check-production-capacity-governance.py"],
        errors,
    )
    require_contains(
        rel(PR_GATE),
        pr_gate,
        ["PR_PRODUCTION_CAPACITY_GOVERNANCE", "check-production-capacity-governance.py"],
        errors,
    )
    require_contains(
        rel(FULL_GATE),
        full_gate,
        ["FULL_PRODUCTION_CAPACITY_GOVERNANCE", "check-production-capacity-governance.py"],
        errors,
    )

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
