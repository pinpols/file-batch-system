#!/usr/bin/env python3
"""校验 SLO / SLI 目录、Runbook 入口和治理文档的一致性。"""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "SLO_SLI_CATALOG"
GATE_NAME = "SLO / SLI 目录治理"

CATALOG = ROOT / "docs/runbook/slo-sli-catalog.md"
RUNBOOK_INDEX = ROOT / "docs/runbook/README.md"
APP_GOVERNANCE = ROOT / "docs/architecture/application-governance.md"
MATURITY_ROADMAP = ROOT / "docs/architecture/engineering-maturity-roadmap.md"
PROM_RULES = ROOT / "deploy/docker/observability/prometheus-batch-rules.yml"
HELM_RULES = ROOT / "helm/batch-platform/files/prometheus-batch-rules.yml"

REQUIRED_SLI = [
    "调度准点率",
    "批次日完成率",
    "运行积压深度",
    "Outbox 投递健康",
    "Kafka 消费滞后",
    "文件到达准点率",
    "重试恢复率",
    "Worker 在线健康",
    "下游 readiness 成功率",
    "数据库迁移一致性",
]

REQUIRED_ALERTS = [
    "Outbox backlog",
    "Kafka lag",
    "file arrival SLA violation",
    "ONLINE workers have stale heartbeats",
    "worker lease fast-retry storm",
    "readiness timeout",
    "oldest queued partition waited",
    "PG lock / Hikari saturation",
]

REQUIRED_RUNBOOK_LINKS = [
    "trigger-operations.md",
    "dependency-aware-fire.md",
    "batch-day-gate-howto.md",
    "heavy-workload-operations.md",
    "autoscaling-strategy.md",
    "outbox-architecture.md",
    "compensation-cleanup.md",
    "base-services-deployment.md",
    "event-driven-arrival.md",
    "worker-stage-coverage.md",
    "forensic-replay-howto.md",
    "rolling-upgrade-workers.md",
    "asset-partition-readiness.md",
    "db-migration-checklist.md",
    "database-schema-governance.md",
]

REQUIRED_EVIDENCE_TERMS = [
    "commit",
    "镜像 digest",
    "采样开始 / 结束时间",
    "Outbox",
    "Kafka lag",
    "Worker 心跳",
    "readiness timeout",
]

REQUIRED_EXCLUSIONS = [
    "单次本地 smoke",
    "已跳过、已取消或 pending 的 CI job",
    "前端 mock 接口通过的 Playwright 用例",
    "没有目标环境接收端的告警配置",
]


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


def main() -> int:
    errors: list[str] = []

    catalog = read(CATALOG, errors)
    runbook_index = read(RUNBOOK_INDEX, errors)
    app_governance = read(APP_GOVERNANCE, errors)
    maturity_roadmap = read(MATURITY_ROADMAP, errors)
    prometheus_rules = read(PROM_RULES, errors)
    helm_rules = read(HELM_RULES, errors)

    require_contains(rel(CATALOG), catalog, REQUIRED_SLI, errors)
    require_contains(rel(CATALOG), catalog, REQUIRED_ALERTS, errors)
    require_contains(rel(CATALOG), catalog, REQUIRED_RUNBOOK_LINKS, errors)
    require_contains(rel(CATALOG), catalog, REQUIRED_EVIDENCE_TERMS, errors)
    require_contains(rel(CATALOG), catalog, REQUIRED_EXCLUSIONS, errors)
    require_contains(
        rel(CATALOG),
        catalog,
        [
            "deploy/docker/observability/prometheus-batch-rules.yml",
            "helm/batch-platform/files/prometheus-batch-rules.yml",
            "文档只定义口径",
            "目标环境负责给出实际阈值",
        ],
        errors,
    )

    require_contains(
        rel(RUNBOOK_INDEX),
        runbook_index,
        ["slo-sli-catalog.md", "SLO / SLI 目录", "观测与韧性"],
        errors,
    )
    require_contains(
        rel(APP_GOVERNANCE),
        app_governance,
        ["slo-sli-catalog.md", "Prometheus 规则", "告警接收端"],
        errors,
    )
    require_contains(
        rel(MATURITY_ROADMAP),
        maturity_roadmap,
        ["slo-sli-catalog.md", "可观测性和 SLO", "告警阈值"],
        errors,
    )
    require_contains(
        rel(PROM_RULES),
        prometheus_rules,
        ["Outbox", "Kafka", "worker", "readiness"],
        errors,
    )
    require_contains(
        rel(HELM_RULES),
        helm_rules,
        ["Outbox", "Kafka", "worker", "readiness"],
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
