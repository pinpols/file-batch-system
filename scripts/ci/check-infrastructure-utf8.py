#!/usr/bin/env python3
"""检查应用、基础服务及测试容器的 UTF-8 locale 与数据库编码配置。"""

from __future__ import annotations

from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
GATE_CODE = "INFRASTRUCTURE_UTF8"
GATE_NAME = "基础环境 UTF-8 配置"
COMPOSE_LOCALE = "${BATCH_LOCALE:-C.UTF-8}"
COMPOSE_SERVICES = ("postgres-primary", "kafka", "minio", "valkey")
KAFKA_HA_SERVICES = ("kafka", "kafka-2", "kafka-3", "kafka-init")
APP_SERVICES = (
    "console-api", "trigger", "orchestrator", "worker-import", "worker-export",
    "worker-process", "worker-dispatch", "worker-atomic",
)
TEST_SERVICES = ("sftp", "mockserver")
TEST_CONTAINER_FACTORIES = (
    "TestPostgresContainers.java",
    "TestKafkaContainers.java",
    "MinioObjectStoreContainer.java",
    "TestValkeyContainers.java",
)


def load_yaml(path: Path) -> dict:
    return yaml.safe_load(path.read_text(encoding="utf-8")) or {}


def check_service_env(errors: list[str], compose: dict, service_names: tuple[str, ...], label: str) -> None:
    services = compose.get("services", {})
    for name in service_names:
        service = services.get(name)
        if not isinstance(service, dict):
            errors.append(f"{label}: missing service {name}")
            continue
        environment = service.get("environment", {})
        for key in ("LANG", "LC_ALL"):
            value = environment.get(key) if isinstance(environment, dict) else None
            if value != COMPOSE_LOCALE:
                errors.append(f"{label}:{name} {key} must derive from BATCH_LOCALE")


def main() -> int:
    errors: list[str] = []
    compose_path = ROOT / "docker-compose.yml"
    compose = load_yaml(compose_path)
    check_service_env(errors, compose, COMPOSE_SERVICES, compose_path.name)

    kafka_ha_path = ROOT / "docker-compose.kafka-ha.yml"
    check_service_env(errors, load_yaml(kafka_ha_path), KAFKA_HA_SERVICES, kafka_ha_path.name)

    app_compose_path = ROOT / "deploy/docker/compose/app.yml"
    app_compose = load_yaml(app_compose_path)
    check_service_env(errors, app_compose, APP_SERVICES, app_compose_path.name)

    dockerfile = (ROOT / "deploy/docker/Dockerfile.app").read_text(encoding="utf-8")
    for line in ('BATCH_LOCALE="C.UTF-8"', 'LANG="C.UTF-8"', 'LC_ALL="C.UTF-8"'):
        if line not in dockerfile:
            errors.append(f"Dockerfile.app must set {line}")

    maven_root = (ROOT / "pom.xml").read_text(encoding="utf-8")
    if "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>" not in maven_root:
        errors.append("root pom.xml must declare UTF-8 source encoding for Java applications and SDKs")
    if "<encoding>UTF-8</encoding>" not in maven_root:
        errors.append("root pom.xml compiler plugin must explicitly use UTF-8")

    sdk_readme = (ROOT / "sdk/README.md").read_text(encoding="utf-8")
    if "HTTP/JSON 与 Kafka 协议文本统一 UTF-8" not in sdk_readme:
        errors.append("sdk/README.md must define UTF-8 for SDK source/config/protocol text")
    if "显式配置其他字符集" not in sdk_readme:
        errors.append("sdk/README.md must preserve explicit partner-file charset support")

    env_file = (ROOT / ".env.example").read_text(encoding="utf-8")
    if "BATCH_LOCALE=C.UTF-8" not in env_file.splitlines():
        errors.append(".env.example must define BATCH_LOCALE=C.UTF-8")

    for name in ("postgres-primary", "postgres-biz-shard-1"):
        service = compose.get("services", {}).get(name, {})
        environment = service.get("environment", {})
        init_args = environment.get("POSTGRES_INITDB_ARGS", "")
        if "--encoding=UTF8" not in init_args:
            errors.append(f"docker-compose.yml:{name} must initialize new databases with UTF8")

    test_compose_path = ROOT / "deploy/docker/compose/test.yml"
    check_service_env(errors, load_yaml(test_compose_path), TEST_SERVICES, test_compose_path.name)

    helm_values = load_yaml(ROOT / "helm/batch-platform/values.yaml")
    if helm_values.get("batchLocale") != "C.UTF-8":
        errors.append("Helm batchLocale default must be C.UTF-8")
    configmap = (ROOT / "helm/batch-platform/templates/configmap.yaml").read_text(encoding="utf-8")
    for key in ("BATCH_LOCALE", "LANG", "LC_ALL"):
        if f"  {key}: {{{{ .Values.batchLocale | quote }}}}" not in configmap:
            errors.append(f"Helm ConfigMap must derive {key} from batchLocale")

    locale_source = (ROOT / "scripts/lib/env-common.sh").read_text(encoding="utf-8")
    for line in ('export LANG="$BATCH_LOCALE"', 'export LC_ALL="$BATCH_LOCALE"'):
        if line not in locale_source:
            errors.append(f"scripts/lib/env-common.sh must enforce {line}")
    provision_script = (ROOT / "scripts/local/provision-biz-shard.sh").read_text(encoding="utf-8")
    for line in ('-e LANG="$BATCH_LOCALE"', '-e LC_ALL="$BATCH_LOCALE"'):
        if line not in provision_script:
            errors.append(f"scripts/local/provision-biz-shard.sh must pass {line} to PostgreSQL")

    locale_constant = "TestContainerImages.UTF8_LOCALE"
    for filename in TEST_CONTAINER_FACTORIES:
        matches = list((ROOT / "batch-test-support/src/main/java").rglob(filename))
        if not matches:
            errors.append(f"missing Testcontainers factory: {filename}")
            continue
        source = matches[0].read_text(encoding="utf-8")
        for key in ("LANG", "LC_ALL"):
            call = f'withEnv("{key}", {locale_constant})'
            if call not in source:
                errors.append(f"{filename} must configure {key} from {locale_constant}")
        if filename == "TestPostgresContainers.java" and '.withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8")' not in source:
            errors.append("TestPostgresContainers must initialize test databases as UTF8")

    if errors:
        print(f"❌ 不通过 | code={GATE_CODE} | gate={GATE_NAME} | exit_code=1")
        for error in errors:
            print(f"  - {error}")
        return 1

    print(
        f"✅ 通过 | code={GATE_CODE} | gate={GATE_NAME} "
        f"| service_configs={len(COMPOSE_SERVICES) + len(KAFKA_HA_SERVICES) + len(APP_SERVICES) + len(TEST_SERVICES)} "
        f"testcontainers={len(TEST_CONTAINER_FACTORIES)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
