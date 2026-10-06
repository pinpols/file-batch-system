#!/usr/bin/env python3
"""在不依赖 Kubernetes 集群的情况下校验 KEDA Worker 自动扩缩容契约。

topic 名从 BatchTopics.java 读取、consumerGroup 从各 worker 的 application.yml 读取，
本脚本**不复制**这两类字面量 —— 否则就成了「校验脚本与自己的副本一致」的第三份真相：
改了 BatchTopics.java 却忘了改这里时，守护仍会给出虚假的通过。

consumerGroup 必须与 worker 实际订阅用的 group 一致：KEDA 按该 group 的 lag 扩缩，
名字漂移会让扩缩容读错 lag 而静默失效。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BATCH_TOPICS = (
    ROOT / "batch-common/src/main/java/io/github/pinpols/batch/common/kafka/BatchTopics.java"
)
# topic 名权威：BatchTopics.TASK_DISPATCH_<WORKER>
TOPIC_CONSTANT = re.compile(
    r'public static final String TASK_DISPATCH_([A-Z0-9_]+) = "([a-z0-9._-]+)";'
)
# consumer group 权威：worker 自己的 application.yml 默认值
CONSUMER_GROUP_ID = re.compile(
    r"consumer-group-id:\s*\$\{BATCH_WORKER_[A-Z0-9_]+_CONSUMER_GROUP_ID:([a-z0-9._-]+)\}"
)
GATE_CODE = "KEDA_AUTOSCALING"
GATE_NAME = "KEDA 自动扩缩容"


def dispatch_topics() -> dict[str, str]:
    """返回 {worker: topic}，取自 BatchTopics.java 的 TASK_DISPATCH_* 常量。"""
    return {
        name.lower(): value
        for name, value in TOPIC_CONSTANT.findall(BATCH_TOPICS.read_text(encoding="utf-8"))
    }


def consumer_group(worker: str) -> str | None:
    """返回 worker 在 application.yml 里声明的 consumer-group-id 默认值。"""
    path = ROOT / f"batch-worker/{worker}/src/main/resources/application.yml"
    if not path.exists():
        return None
    match = CONSUMER_GROUP_ID.search(path.read_text(encoding="utf-8"))
    return match.group(1) if match else None


def main() -> int:
    errors: list[str] = []

    topics = dispatch_topics()
    if not topics:
        errors.append(
            f"{BATCH_TOPICS.relative_to(ROOT)}: 未解析到 TASK_DISPATCH_* 常量（topic 名权威缺失）"
        )

    example = (ROOT / "helm/batch-platform/examples/values-autoscale.yaml").read_text(
        encoding="utf-8"
    )
    values = (ROOT / "helm/batch-platform/values.yaml").read_text(encoding="utf-8")

    for worker, topic in topics.items():
        group = consumer_group(worker)
        if group is None:
            errors.append(
                f"batch-worker/{worker}/src/main/resources/application.yml: "
                "未找到 consumer-group-id 默认值（consumerGroup 权威缺失）"
            )
            continue

        template_path = ROOT / f"helm/batch-platform/templates/worker-{worker}.yaml"
        if not template_path.exists():
            errors.append(f"{template_path.relative_to(ROOT)}: missing template")
            continue
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
