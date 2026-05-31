#!/usr/bin/env python3
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def list_dependencies(pom_path: Path) -> list[tuple[str, str, str | None]]:
    root = ET.parse(pom_path).getroot()
    result: list[tuple[str, str, str | None]] = []
    for dependency in root.findall("./m:dependencies/m:dependency", NS):
        group_id = dependency.findtext("m:groupId", default="", namespaces=NS).strip()
        artifact_id = dependency.findtext("m:artifactId", default="", namespaces=NS).strip()
        scope = dependency.findtext("m:scope", default="", namespaces=NS).strip() or None
        result.append((group_id, artifact_id, scope))
    return result


def runtime_dependencies(module: str) -> set[tuple[str, str]]:
    pom_path = ROOT / module / "pom.xml"
    return {
        (group_id, artifact_id)
        for group_id, artifact_id, scope in list_dependencies(pom_path)
        if scope != "test"
    }


def main() -> int:
    errors: list[str] = []

    grandfathered_common_runtime = {
        ("io.minio", "minio"),
        ("io.micrometer", "micrometer-tracing-bridge-otel"),
        ("io.opentelemetry", "opentelemetry-exporter-otlp"),
        ("io.opentelemetry", "opentelemetry-exporter-sender-jdk"),
    }
    forbidden_common_runtime = {
        ("org.springframework.ai", "spring-ai-starter-model-openai"),
        ("org.apache.poi", "poi-ooxml"),
    }

    common_runtime = runtime_dependencies("batch-common")
    for forbidden in sorted(forbidden_common_runtime):
        if forbidden in common_runtime:
            errors.append(
                "batch-common must stay lightweight; forbidden runtime dependency found: "
                f"{forbidden[0]}:{forbidden[1]}"
            )
    # Historical runtime weight in batch-common is tolerated for now, but no new categories should be added.
    for existing in sorted(grandfathered_common_runtime):
        if existing not in common_runtime:
            print(
                "INFO: historical batch-common runtime dependency has been removed: "
                f"{existing[0]}:{existing[1]}",
                file=sys.stderr,
            )

    jdbc_marker = ("org.springframework.boot", "spring-boot-starter-jdbc")
    data_jdbc_marker = ("org.springframework.boot", "spring-boot-starter-data-jdbc")
    mybatis_marker = ("org.mybatis.spring.boot", "mybatis-spring-boot-starter")

    mybatis_jdbc_modules = {"batch-console-api", "batch-orchestrator"}
    for module in sorted(mybatis_jdbc_modules):
        deps = runtime_dependencies(module)
        missing = [
            name
            for name, marker in (
                ("spring-boot-starter-jdbc", jdbc_marker),
                ("mybatis-spring-boot-starter", mybatis_marker),
            )
            if marker not in deps
        ]
        if missing:
            errors.append(
                f"{module} must carry JDBC + MyBatis runtime markers, but missing: {', '.join(missing)}"
            )

    non_data_jdbc_modules = {
        "batch-console-api",
        "batch-orchestrator",
        "batch-trigger",
        "batch-worker-core",
        "batch-worker-import",
        "batch-worker-export",
        "batch-worker-process",
        "batch-worker-dispatch",
        "batch-worker-atomic",
    }
    for module in sorted(non_data_jdbc_modules):
        deps = runtime_dependencies(module)
        if data_jdbc_marker in deps:
            errors.append(
                f"{module} must not add spring-boot-starter-data-jdbc (platform persistence is MyBatis-only; see ADR-001)"
            )

    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1

    print("OK: dependency boundaries satisfied.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
