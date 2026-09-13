#!/usr/bin/env python3
"""Guard the production logging and telemetry delivery contract."""

from __future__ import annotations

import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
APP_SERVICES = (
    "console-api",
    "trigger",
    "orchestrator",
    "worker-import",
    "worker-export",
    "worker-process",
    "worker-dispatch",
    "worker-atomic",
)
GRAFANA_DASHBOARDS = (
    "grafana-dashboard-batch.json",
    "grafana-dashboard-batch-coverage.json",
    "grafana-dashboard-batch-mainline.json",
    "grafana-dashboard-batch-sre.json",
)
WORKLOAD_TEMPLATES = tuple(
    ROOT / "helm/batch-platform/templates" / name
    for name in (
        "console-api.yaml",
        "trigger.yaml",
        "orchestrator.yaml",
        "worker-import.yaml",
        "worker-export.yaml",
        "worker-process.yaml",
        "worker-dispatch.yaml",
        "worker-atomic.yaml",
        "worker-tenant.yaml",
    )
)


def load_yaml(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


def nested(value: dict, *path: str):
    current = value
    for key in path:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def main() -> int:
    errors: list[str] = []
    if (ROOT / "docker-compose.app.yml").exists():
        errors.append(
            "obsolete docker-compose.app.yml is forbidden; use deploy/docker/compose/app.yml"
        )
    app_compose = load_yaml(ROOT / "deploy/docker/compose/app.yml")
    base_compose = load_yaml(ROOT / "docker-compose.yml")
    obs_compose = load_yaml(ROOT / "deploy/docker/compose/observability.yml")

    for document, services in (
        (app_compose, APP_SERVICES),
        (base_compose, tuple((base_compose.get("services") or {}).keys())),
        (obs_compose, tuple((obs_compose.get("services") or {}).keys())),
    ):
        for service_name in services:
            service = nested(document, "services", service_name) or {}
            logging = service.get("logging") or {}
            if logging.get("driver") != "local":
                errors.append(f"{service_name}: Docker logging driver must be local")
            options = logging.get("options") or {}
            if not options.get("max-size") or not options.get("max-file"):
                errors.append(f"{service_name}: Docker logs must define max-size and max-file")

    app_text = (ROOT / "deploy/docker/compose/app.yml").read_text(encoding="utf-8")
    if "LOGGING_FILE_NAME" in app_text or "./logs/current/docker:/app/logs" in app_text:
        errors.append("application containers must not duplicate stdout into mounted log files")

    grafana_volumes = nested(obs_compose, "services", "grafana", "volumes") or []
    mounted_dashboards = {Path(str(volume).split(":", 1)[0]).name for volume in grafana_volumes}
    for dashboard in GRAFANA_DASHBOARDS:
        if dashboard not in mounted_dashboards:
            errors.append(f"Grafana dashboard is not mounted: {dashboard}")
        dashboard_data = load_yaml(ROOT / "deploy/docker/observability" / dashboard)
        if not dashboard_data.get("uid"):
            errors.append(f"Grafana dashboard must define a stable uid: {dashboard}")

    collector = load_yaml(ROOT / "deploy/docker/observability/otel-collector.yml")
    extensions = collector.get("extensions") or {}
    processors = collector.get("processors") or {}
    if "file_storage" not in extensions:
        errors.append("Collector must configure file_storage")
    for processor in ("memory_limiter", "redaction", "tail_sampling", "batch"):
        if processor not in processors:
            errors.append(f"Collector processor missing: {processor}")
    for exporter_name, exporter in (collector.get("exporters") or {}).items():
        if not exporter.get("sending_queue") or not exporter.get("retry_on_failure"):
            errors.append(f"Collector exporter {exporter_name} must use queue and retry")

    helm_collector = (
        ROOT / "helm/batch-platform/templates/otel-collector.yaml"
    ).read_text(encoding="utf-8")
    for exporter_name in ("otlp/tempo", "otlp/jaeger", "otlphttp/loki"):
        if exporter_name not in helm_collector:
            errors.append(f"Helm Collector exporter missing: {exporter_name}")
    if "exporters: [otlp/tempo, otlp/jaeger]" not in helm_collector:
        errors.append("Helm Collector traces must be delivered to Tempo and Jaeger")
    if "address: 0.0.0.0:8888" in helm_collector or "readers:" not in helm_collector:
        errors.append("Helm Collector self-metrics must use the current pull reader schema")
    if "send_batch_max_size: 2048" not in helm_collector:
        errors.append("Helm Collector batch processor must cap its maximum send batch size")

    prod = load_yaml(ROOT / "helm/values-prod.yaml")
    if nested(prod, "otel", "enabled") is not True:
        errors.append("production Helm overlay must enable OpenTelemetry")
    endpoint = str(nested(prod, "otel", "endpoint") or "")
    if not endpoint or "batch-platform-otel-collector" in endpoint:
        errors.append("production OTLP endpoint must target the external Collector")
    if str(nested(prod, "otel", "samplingProbability")) != "1.0":
        errors.append("production head sampling must be 1.0 when tail sampling is authoritative")
    prod_profile = load_yaml(
        ROOT / "batch-common/src/main/resources/application-prod.yml"
    )
    profile_sampling = nested(
        prod_profile, "management", "tracing", "sampling", "probability"
    )
    if str(profile_sampling) != "${OTEL_SAMPLING_PROBABILITY:1.0}":
        errors.append("production profile must default head sampling to 1.0")
    helm_config = (ROOT / "helm/batch-platform/templates/configmap.yaml").read_text(
        encoding="utf-8"
    )
    for required_url in ("BATCH_TRIGGER_BASE_URL", "BATCH_WORKER_ATOMIC_BASE_URL"):
        if required_url not in helm_config:
            errors.append(f"Helm ConfigMap must inject {required_url}")

    for template in WORKLOAD_TEMPLATES:
        text = template.read_text(encoding="utf-8")
        helper_root = "$root" if template.name == "worker-tenant.yaml" else "."
        for helper in ("diagnosticsVolumeMount", "diagnosticsVolume"):
            marker = f'include "batch-platform.{helper}" {helper_root}'
            if marker not in text:
                errors.append(f"{template.name}: missing {helper}")

    common_pom = (ROOT / "batch-common/pom.xml").read_text(encoding="utf-8")
    if "spring-boot-starter-opentelemetry" not in common_pom:
        errors.append("batch-common must use Spring Boot OpenTelemetry starter")
    if "opentelemetry-logback-appender-1.0" not in common_pom:
        errors.append("batch-common must bridge Logback events into OpenTelemetry logs")
    bridge = (
        ROOT
        / "batch-common/src/main/java/io/github/pinpols/batch/common/logging/OpenTelemetryLogbackBridge.java"
    ).read_text(encoding="utf-8")
    if 'setCaptureMdcAttributes("*")' not in bridge:
        errors.append("OpenTelemetry Logback bridge must preserve structured MDC fields")
    defaults = load_yaml(
        ROOT / "batch-common/src/main/resources/batch-defaults.yml"
    )
    master_switch = "${MANAGEMENT_OPENTELEMETRY_ENABLED:false}"
    for path in (
        ("management", "opentelemetry", "enabled"),
        ("management", "otlp", "metrics", "export", "enabled"),
        ("management", "tracing", "export", "otlp", "enabled"),
        ("management", "logging", "export", "otlp", "enabled"),
    ):
        if nested(defaults, *path) != master_switch:
            errors.append(
                f"{'.'.join(path)} must follow MANAGEMENT_OPENTELEMETRY_ENABLED"
            )
    for deprecated_signal in ("tracing", "logging"):
        if nested(defaults, "management", "otlp", deprecated_signal) is not None:
            errors.append(
                f"deprecated management.otlp.{deprecated_signal} properties are forbidden"
            )
    if (ROOT / "batch-console-api/src/main/resources/logback-spring.xml").exists():
        errors.append(
            "console-api must use Boot LoggingSystem; custom logback overrides structured output"
        )

    if errors:
        print("Observability contract failed:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("Observability contract passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
