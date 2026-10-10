#!/usr/bin/env python3
"""Guard the production logging and telemetry delivery contract."""

from __future__ import annotations

import sys
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "OBSERVABILITY_CONTRACT"
GATE_NAME = "可观测性契约"
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
    "grafana-dashboard-batch-capacity.json",
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

    runtime_env = app_compose.get("x-app-runtime-env") or {}
    if runtime_env.get("MANAGEMENT_LOGGING_EXPORT_OTLP_ENABLED") != (
        "${MANAGEMENT_OPENTELEMETRY_ENABLED:-false}"
    ):
        errors.append("Compose must bind Spring Boot OTLP logging to the OpenTelemetry switch")
    console_env = nested(app_compose, "services", "console-api", "environment") or {}
    if console_env.get("BATCH_CONSOLE_ALERTMANAGER_BEARER_TOKEN") != (
        "${BATCH_CONSOLE_ALERTMANAGER_BEARER_TOKEN:-}"
    ):
        errors.append("Console API must receive the Alertmanager webhook bearer token")
    alertmanager = nested(obs_compose, "services", "alertmanager") or {}
    if alertmanager.get("entrypoint") != [
        "/bin/sh",
        "/usr/local/bin/render-alertmanager-config.sh",
    ]:
        errors.append("Alertmanager must render and validate its shared bearer token at startup")
    if (alertmanager.get("environment") or {}).get(
        "BATCH_CONSOLE_ALERTMANAGER_BEARER_TOKEN"
    ) != "${BATCH_CONSOLE_ALERTMANAGER_BEARER_TOKEN:-}":
        errors.append("Alertmanager must receive the same webhook bearer token as Console API")
    if not (ROOT / "scripts/ops/render-alertmanager-config.sh").is_file():
        errors.append("Alertmanager token renderer is missing")

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

    volume_services = (
        (base_compose, "minio-volume-init", "1001:1001"),
        (obs_compose, "tempo-init", "10001:10001"),
        (obs_compose, "otel-collector-init", "10001:10001"),
    )
    for document, name, expected_user in volume_services:
        service = nested(document, "services", name) or {}
        if service.get("user") != expected_user:
            errors.append(f"{name}: volume preparation must run as {expected_user}")
        if nested(service, "build", "dockerfile") != "deploy/docker/Dockerfile.volume-init":
            errors.append(f"{name}: volume preparation image is missing")
        if not service.get("read_only") or service.get("cap_add") or service.get("privileged"):
            errors.append(f"{name}: volume preparation must not require root privileges")

    runtime_volumes = (
        (base_compose, "minio", "1001:1001", "minio-data", "/bitnami/minio/data"),
        (obs_compose, "tempo", "10001:10001", "tempo-data", "/var/tempo"),
        (
            obs_compose,
            "otel-collector",
            "10001:10001",
            "otel-collector-data",
            "/var/lib/otelcol",
        ),
    )
    for document, name, expected_user, source, target in runtime_volumes:
        service = nested(document, "services", name) or {}
        if service.get("user") != expected_user:
            errors.append(f"{name}: runtime must run as {expected_user}")
        volumes = service.get("volumes") or []
        if not any(
            isinstance(volume, dict)
            and volume.get("source") == source
            and volume.get("target") == target
            and nested(volume, "volume", "nocopy") is True
            for volume in volumes
        ):
            errors.append(f"{name}: {source} must mount at {target} with nocopy")
    if nested(base_compose, "services", "minio-init", "user") != "1001:1001":
        errors.append("minio-init: bucket initialization must run as 1001:1001")

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

    if nested(obs_compose, "services", "grafana", "environment", "GF_AUTH_ANONYMOUS_ENABLED") != "false":
        errors.append("Compose Grafana anonymous access must stay disabled")
    if nested(obs_compose, "services", "grafana", "environment", "GF_SECURITY_ADMIN_PASSWORD") != "${GRAFANA_ADMIN_PASSWORD:?GRAFANA_ADMIN_PASSWORD is required}":
        errors.append("Compose Grafana must require a dedicated admin password")
    published_services = (
        "prometheus",
        "alertmanager",
        "jaeger",
        "tempo",
        "loki",
        "otel-collector",
        "grafana",
        "redis-exporter",
        "postgres-exporter",
        "kafka-exporter",
        "node-exporter",
        "cadvisor",
    )
    for service_name in published_services:
        ports = nested(obs_compose, "services", service_name, "ports") or []
        for port in ports:
            rendered = str(port.get("published", "")) if isinstance(port, dict) else str(port)
            if "OBSERVABILITY_BIND_IP" not in rendered:
                errors.append(f"{service_name}: published ports must use OBSERVABILITY_BIND_IP")
    for service_name in ("jaeger", "tempo"):
        ports = nested(obs_compose, "services", service_name, "ports") or []
        if any(str(port) == "4317" for port in ports):
            errors.append(f"{service_name}: OTLP gRPC must not be randomly published to the host")
    secret_file = nested(obs_compose, "secrets", "alertmanager-bearer-token", "file")
    if secret_file != "${BATCH_CONSOLE_ALERTMANAGER_BEARER_TOKEN_FILE:?Set BATCH_CONSOLE_ALERTMANAGER_BEARER_TOKEN_FILE to the shared bearer-token file}":
        errors.append("Compose Alertmanager bearer token must come from a required secret file")
    observability_runbook = (
        ROOT / "docs/runbook/observability-stack.md"
    ).read_text(encoding="utf-8")
    for permission_step in (
        "umask 077",
        "chmod 0700 secrets/observability",
        "chmod 0444 secrets/observability/console-bearer-token",
    ):
        if permission_step not in observability_runbook:
            errors.append(
                f"Observability runbook must protect and expose the shared secret safely: {permission_step}"
            )
    for service_name, target in (
        ("alertmanager", "alertmanager-bearer-token"),
        ("console-api", "batch.console.alertmanager.bearer-token"),
    ):
        service_secrets = nested(obs_compose, "services", service_name, "secrets") or []
        if not any(
            isinstance(secret, dict)
            and secret.get("source") == "alertmanager-bearer-token"
            and secret.get("target") == target
            for secret in service_secrets
        ):
            errors.append(f"{service_name}: shared Alertmanager token secret is not mounted")
    alertmanager_config = load_yaml(
        ROOT / "deploy/docker/observability/alertmanager-batch-template.yml"
    )
    alertmanager_receivers = alertmanager_config.get("receivers") or []
    first_webhook = (
        (alertmanager_receivers[0].get("webhook_configs") or [{}])[0]
        if alertmanager_receivers
        else {}
    )
    if nested(
        first_webhook, "http_config", "authorization", "credentials_file"
    ) != "/run/secrets/alertmanager-bearer-token":
        errors.append("Alertmanager webhook must read its bearer token from the mounted secret")
    for service_name, expected_profile in (
        ("node-exporter", "host-metrics"),
        ("cadvisor", "container-metrics"),
    ):
        profiles = nested(obs_compose, "services", service_name, "profiles") or []
        if expected_profile not in profiles:
            errors.append(f"{service_name}: high-scope metrics collection must be opt-in")
    prometheus_entrypoint = (
        ROOT / "deploy/docker/observability/prometheus-entrypoint.sh"
    ).read_text(encoding="utf-8")
    for target in (
        "console-api 18080 batch-console-api",
        "trigger 18081 batch-trigger",
        "orchestrator 18082 batch-orchestrator",
        "worker-import 18083 batch-worker-import",
        "worker-export 18084 batch-worker-export",
        "worker-dispatch 18085 batch-worker-dispatch",
        "worker-process 18086 batch-worker-process",
        "worker-atomic 18087 batch-worker-atomic",
    ):
        if target not in prometheus_entrypoint:
            errors.append(f"Prometheus container target is missing or has a wrong port: {target}")

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
    site_fixture = load_yaml(
        ROOT / "helm/batch-platform/examples/values-production-topology-test.yaml"
    )
    site_endpoint = str(nested(site_fixture, "otel", "endpoint") or "")
    if endpoint and "batch-platform-otel-collector" in endpoint:
        errors.append("production OTLP endpoint must not target the chart's disabled Collector")
    if not endpoint and (not site_endpoint or "batch-platform-otel-collector" in site_endpoint):
        errors.append("site topology fixture must provide a reachable external Collector endpoint")
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
        ("management", "tracing", "export", "otlp", "enabled"),
        ("management", "logging", "export", "otlp", "enabled"),
    ):
        if nested(defaults, *path) != master_switch:
            errors.append(
                f"{'.'.join(path)} must follow MANAGEMENT_OPENTELEMETRY_ENABLED"
            )
    metrics_export = nested(
        defaults, "management", "otlp", "metrics", "export", "enabled"
    )
    if metrics_export is not False:
        errors.append(
            "management.otlp.metrics.export.enabled must stay false; Prometheus owns metrics"
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

    alert_rules = load_yaml(
        ROOT / "deploy/docker/observability/prometheus-batch-rules.yml"
    )
    alert_names = {
        rule.get("alert")
        for group in alert_rules.get("groups") or []
        for rule in group.get("rules") or []
    }
    for required_alert in (
        "BatchCoreServiceTargetMissing",
        "BatchAlertmanagerDown",
        "BatchAlertmanagerNotificationFailures",
        "BatchAlertmanagerConfigReloadFailed",
        "BatchAlertmanagerNotifySkipped",
        "BatchAlertmanagerDeliveryFailed",
    ):
        if required_alert not in alert_names:
            errors.append(f"platform alert rule missing: {required_alert}")
    prometheus = load_yaml(ROOT / "deploy/docker/observability/prometheus.yml")
    alertmanager_scraped = any(
        scrape.get("job_name") == "alertmanager"
        and any(
            "alertmanager:9093" in (target_group.get("targets") or [])
            for target_group in scrape.get("static_configs") or []
        )
        for scrape in prometheus.get("scrape_configs") or []
    )
    if not alertmanager_scraped:
        errors.append("Docker Prometheus must scrape Alertmanager's metrics endpoint")
    service_monitor = (
        ROOT / "helm/batch-platform/templates/servicemonitor.yaml"
    ).read_text(encoding="utf-8")
    if (
        "targetLabel: job" not in service_monitor
        or "replacement: batch-{{ $component }}" not in service_monitor
    ):
        errors.append(
            "Helm app targets must use the same stable batch-<component> job labels as Docker"
        )

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1
    print(f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
