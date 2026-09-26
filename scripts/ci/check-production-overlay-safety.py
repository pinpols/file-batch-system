#!/usr/bin/env python3
"""Validate production Helm values keep the platform fail-closed by default.

The chart intentionally keeps development defaults permissive.  This check makes
the production overlay an executable contract so a release cannot silently fall
back to development security, quota, or network settings.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
CHART_VALUES = ROOT / "helm/batch-platform/values.yaml"
PROD_VALUES = ROOT / "helm/values-prod.yaml"
LOCAL_K8S_VALUES = ROOT / "helm/batch-platform/examples/values-local-k8s.yaml"
HEAVY_WORKER_VALUES = ROOT / "helm/batch-platform/examples/values-heavy-worker-pools.yaml"
GATE_CODE = "PRODUCTION_OVERLAY_SAFETY"
GATE_NAME = "生产 Helm 覆盖安全"

GC_ENABLE_PATTERN = re.compile(
    r"-XX:\+Use(?:Serial|Parallel|G1|Z|Shenandoah|Epsilon)GC(?:\s|$)"
)


def get(values: dict, *path: str):
    current = values
    for key in path:
        if not isinstance(current, dict) or key not in current:
            return None
        current = current[key]
    return current


def is_true(value: object) -> bool:
    """Accept YAML booleans and the string form used by existing env-bound values."""
    return value is True or (isinstance(value, str) and value.strip().lower() == "true")


def merge_values(base: dict, overlay: dict) -> dict:
    """按本检查涉及的 Helm map 递归覆盖语义合并 values。"""
    merged = dict(base)
    for key, value in overlay.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = merge_values(merged[key], value)
        else:
            merged[key] = value
    return merged


def enabled_collectors(options: str) -> set[str]:
    return {match.group(0).strip() for match in GC_ENABLE_PATTERN.finditer(options)}


def validate_gc_configuration(label: str, values: dict, errors: list[str]) -> None:
    """禁止公共层选择 GC，并确保每个最终服务恰好选择一种 GC。"""
    common_java_opts = str(get(values, "javaOpts") or "")
    common_collectors = enabled_collectors(common_java_opts)
    if common_collectors:
        errors.append(
            f"{label} javaOpts must not select a garbage collector; use service.javaOptsExtra: "
            f"{', '.join(sorted(common_collectors))}"
        )

    for service_name in (
        "consoleApi",
        "trigger",
        "orchestrator",
        "workerImport",
        "workerExport",
        "workerProcess",
        "workerAtomic",
        "workerDispatch",
    ):
        service_java_opts = str(get(values, service_name, "javaOptsExtra") or "")
        collectors = enabled_collectors(f"{common_java_opts} {service_java_opts}")
        if len(collectors) != 1:
            errors.append(
                f"{label} {service_name} must select exactly one garbage collector: "
                f"{', '.join(sorted(collectors)) or 'none'}"
            )

    for pool_key in ("tenantWorkerPools", "workerResourcePools"):
        for index, pool in enumerate(get(values, pool_key) or []):
            pool_java_opts = str(pool.get("javaOptsExtra") or "-XX:+UseG1GC")
            collectors = enabled_collectors(f"{common_java_opts} {pool_java_opts}")
            if len(collectors) != 1:
                errors.append(
                    f"{label} {pool_key}[{index}] must select exactly one garbage collector: "
                    f"{', '.join(sorted(collectors)) or 'none'}"
                )


def main() -> int:
    chart_values = yaml.safe_load(CHART_VALUES.read_text(encoding="utf-8")) or {}
    prod_values = yaml.safe_load(PROD_VALUES.read_text(encoding="utf-8")) or {}
    local_k8s_values = yaml.safe_load(LOCAL_K8S_VALUES.read_text(encoding="utf-8")) or {}
    heavy_worker_values = yaml.safe_load(HEAVY_WORKER_VALUES.read_text(encoding="utf-8")) or {}
    values = merge_values(chart_values, prod_values)
    errors: list[str] = []

    validate_gc_configuration("chart defaults", chart_values, errors)
    validate_gc_configuration("production overlay", values, errors)
    validate_gc_configuration(
        "local Kubernetes example", merge_values(chart_values, local_k8s_values), errors
    )
    validate_gc_configuration(
        "heavy worker pools example", merge_values(chart_values, heavy_worker_values), errors
    )

    required_true = (
        ("security.enforceStrongSecrets", ("security", "enforceStrongSecrets")),
        ("networkPolicy.enabled", ("networkPolicy", "enabled")),
        ("security.loginEncryption.required", ("security", "loginEncryption", "required")),
        ("workerAtomic.productionIsolationRequired", ("workerAtomic", "productionIsolationRequired")),
        ("workerAtomic.requireIsolation", ("workerAtomic", "requireIsolation")),
        ("workerAtomic.isolationAcknowledged", ("workerAtomic", "isolationAcknowledged")),
        ("workerAtomic.networkPolicy.enabled", ("workerAtomic", "networkPolicy", "enabled")),
        ("otel.enabled", ("otel", "enabled")),
        ("serviceMonitor.enabled", ("serviceMonitor", "enabled")),
        ("prometheusRule.enabled", ("prometheusRule", "enabled")),
    )
    for label, path in required_true:
        if not is_true(get(values, *path)):
            errors.append(f"{label} must be true in helm/values-prod.yaml")

    if get(values, "orchestrator", "quota", "redisFailureMode") != "FAIL_CLOSED":
        errors.append("orchestrator.quota.redisFailureMode must be FAIL_CLOSED in production")

    otel_endpoint = get(values, "otel", "endpoint")
    if not isinstance(otel_endpoint, str) or not otel_endpoint.strip():
        errors.append("otel.endpoint must name the production OpenTelemetry Collector")
    elif not is_true(get(values, "otelCollector", "enabled")) and "batch-platform-otel-collector" in otel_endpoint:
        errors.append("otel.endpoint must not target the disabled in-chart Collector")

    try:
        sampling_probability = float(get(values, "otel", "samplingProbability"))
        if sampling_probability != 1.0:
            errors.append("otel.samplingProbability must be 1.0 so Collector tail sampling can retain errors")
    except (TypeError, ValueError):
        errors.append("otel.samplingProbability must be a numeric string")

    for label, path in (
        ("workerAtomic.serviceAccountName", ("workerAtomic", "serviceAccountName")),
        ("workerAtomic.envFromSecretName", ("workerAtomic", "envFromSecretName")),
        ("orchestratorBaseUrl", ("orchestratorBaseUrl",)),
        ("triggerBaseUrl", ("triggerBaseUrl",)),
        ("workerAtomicBaseUrl", ("workerAtomicBaseUrl",)),
    ):
        value = get(values, *path)
        if not isinstance(value, str) or not value.strip():
            errors.append(f"{label} must name an externally managed production resource")
        elif "localhost" in value.lower() or "127.0.0.1" in value:
            errors.append(f"{label} must not use a loopback development address")

    atomic_network = get(values, "workerAtomic", "networkPolicy", "egress") or {}
    for kind in ("postgresql", "kafka"):
        peers = get(atomic_network, kind, "to") or []
        for peer in peers:
            if get(peer, "ipBlock", "cidr") in {"0.0.0.0/0", "::/0"}:
                errors.append(f"workerAtomic.networkPolicy.egress.{kind} must not allow {get(peer, 'ipBlock', 'cidr')}")

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
