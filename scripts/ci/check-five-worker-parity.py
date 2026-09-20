#!/usr/bin/env python3
"""Guard cross-cutting configuration for the five built-in worker types.

Pipeline stages intentionally cover only import/export/process/dispatch. Atomic is a
single-task executor and must still appear in routing, tenant ACLs, observability,
runtime recovery and job configuration surfaces.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKER_TYPES = ("import", "export", "process", "dispatch", "atomic")
WORKER_TYPES_UPPER = tuple(worker_type.upper() for worker_type in WORKER_TYPES)


def read(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


def require_all(errors: list[str], relative: str, markers: tuple[str, ...]) -> None:
    text = read(relative)
    for marker in markers:
        if marker not in text:
            errors.append(f"{relative}: missing {marker!r}")


def main() -> int:
    errors: list[str] = []
    discovered = tuple(
        worker_type
        for worker_type in WORKER_TYPES
        if (ROOT / "batch-worker" / worker_type / "src/main/resources/application.yml").is_file()
    )
    if discovered != WORKER_TYPES:
        errors.append(
            "worker module discovery drifted: "
            f"expected={WORKER_TYPES}, discovered={discovered}"
        )

    lower_csv = ",".join(WORKER_TYPES)
    lag_pattern = "batch-worker-(" + "|".join(WORKER_TYPES) + ")"
    require_all(
        errors,
        "scripts/data/init-tenant-topics.sh",
        (f"WORKER_TYPES:-{lower_csv}",),
    )
    require_all(
        errors,
        "scripts/data/init-tenant-kafka-acl.sh",
        (f"WORKER_TYPES:-{lower_csv}",),
    )
    for rules in (
        "helm/batch-platform/files/prometheus-batch-rules.yml",
        "deploy/docker/observability/prometheus-batch-rules.yml",
    ):
        require_all(errors, rules, (lag_pattern,))

    helm_port_markers = tuple(
        f"BATCH_WORKER_{worker_type}_PORT: {{{{ .Values.worker{worker_type.title()}.port | quote }}}}"
        for worker_type in WORKER_TYPES_UPPER
    )
    require_all(errors, "helm/batch-platform/templates/configmap.yaml", helm_port_markers)

    routing_markers = tuple(f'"{worker_type}"' for worker_type in WORKER_TYPES_UPPER)
    require_all(
        errors,
        "batch-worker/core/src/main/java/io/github/pinpols/batch/worker/core/support/TaskConsumerRoutingPolicy.java",
        routing_markers,
    )

    sim_markers = (
        'local ports=("$ORCHESTRATOR_PORT" "$CONSOLE_API_PORT" "$TRIGGER_PORT" '
        '"$WORKER_IMPORT_PORT" "$WORKER_EXPORT_PORT" "$WORKER_DISPATCH_PORT" '
        '"$WORKER_PROCESS_PORT" "$WORKER_ATOMIC_PORT")',
        "bash scripts/local/restart.sh trigger orchestrator console worker-import "
        "worker-export worker-process worker-dispatch worker-atomic",
        "local groups=(IMPORT EXPORT PROCESS DISPATCH ATOMIC)",
    )
    require_all(errors, "scripts/local/sim-harness.sh", sim_markers)

    sample_markers = tuple(
        f'SCENARIO_{worker_type} = "{worker_type}"' for worker_type in WORKER_TYPES_UPPER
    )
    require_all(
        errors,
        "batch-console-api/src/main/java/io/github/pinpols/batch/console/infrastructure/excel/ConfigPackageSampleDataFactory.java",
        sample_markers,
    )
    require_all(
        errors,
        "batch-console-api/src/main/java/io/github/pinpols/batch/console/domain/job/application/contract/request/JobDefinitionCreateRequest.java",
        ("SUPPORTED_JOB_TYPES.contains(jobType)",),
    )

    guidance = json.loads(read("batch-console-api/src/main/resources/config-package-guidance.json"))
    rows = guidance.get("sheets", {}).get("fiveWorker", {}).get("rows", [])
    guided_types = tuple(row[0] for row in rows if row)
    if guided_types != WORKER_TYPES_UPPER:
        errors.append(
            "config-package-guidance.json: fiveWorker rows must be exactly "
            f"{WORKER_TYPES_UPPER}, got {guided_types}"
        )

    require_all(
        errors,
        "docs/api/console-api.openapi.yaml",
        ("enum: [ALL, IMPORT, EXPORT, PROCESS, DISPATCH, ATOMIC, WORKFLOW]",),
    )
    # Atomic carries stricter isolation and intentionally cannot use the generic resource-pool template.
    require_all(
        errors,
        "docs/architecture/heavy-workload-guarantees.md",
        ("通用模板因此不允许创建 Atomic 资源池",),
    )

    if errors:
        print("Five-worker parity check failed:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("Five-worker parity check passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
