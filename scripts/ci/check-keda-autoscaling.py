#!/usr/bin/env python3
"""在不依赖 Kubernetes 集群的情况下校验 KEDA Worker 自动扩缩容契约。"""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WORKERS = {
    "import": ("batch.task.dispatch.import", "batch-worker-import"),
    "export": ("batch.task.dispatch.export", "batch-worker-export"),
    "process": ("batch.task.dispatch.process", "batch-worker-process"),
    "dispatch": ("batch.task.dispatch.dispatch", "batch-worker-dispatch"),
    "atomic": ("batch.task.dispatch.atomic", "batch-worker-atomic"),
}
GATE_CODE = "KEDA_AUTOSCALING"
GATE_NAME = "KEDA 自动扩缩容"


def main() -> int:
    errors: list[str] = []
    example = (ROOT / "helm/batch-platform/examples/values-autoscale.yaml").read_text(
        encoding="utf-8"
    )
    values = (ROOT / "helm/batch-platform/values.yaml").read_text(encoding="utf-8")

    for worker, (topic, group) in WORKERS.items():
        template_path = ROOT / f"helm/batch-platform/templates/worker-{worker}.yaml"
        template = template_path.read_text(encoding="utf-8")
        expected = (
            "kind: ScaledObject",
            "consumerGroup: {{ $svc.keda.consumerGroup | quote }}",
            "topic: {{ $svc.keda.topic | quote }}",
            "lagThreshold: {{ $svc.keda.lagThreshold | quote }}",
            "cooldownPeriod: {{ $svc.keda.cooldownPeriod | default 120 }}",
            "gracefulShutdownPod",
            "gracefulShutdownLifecycle",
            "not (and $svc.keda $svc.keda.enabled)",
        )
        for marker in expected:
            if marker not in template:
                errors.append(f"{template_path.relative_to(ROOT)}: missing {marker!r}")

        section = f"worker{worker.title()}:"
        if section not in example:
            errors.append(f"values-autoscale.yaml: missing {section}")
        for marker in (f'topic: "{topic}"', f'consumerGroup: "{group}"'):
            if marker not in example:
                errors.append(f"values-autoscale.yaml: missing {marker!r}")
            if marker not in values:
                errors.append(f"values.yaml: missing {marker!r}")

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
