#!/usr/bin/env python3
"""Guard application/domain code from depending on concrete infra clients."""

from __future__ import annotations

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[2]
MAIN_JAVA = "src/main/java"
BUSINESS_LAYERS = ("/application/", "/domain/", "/service/")
ALLOWED_OWNERS = (
    "/config/",
    "/infrastructure/",
    "/mapper/",
    "/mybatis/",
    "/support/",
    "/shared/client/",
    "/common/config/",
    "/common/health/",
    "/common/jdbc/",
    "/common/mq/",
    "/common/rls/",
    "/common/startup/",
    "/common/stateful/",
    "/common/storage/",
    "/common/tenant/",
    "/common/utils/",
)
ALLOWED_OPERATIONAL_FILES = (
    "ConsoleKafkaLagQueryService.java",
    "TriggerLaunchLagMonitor.java",
    "KafkaOffsetSensorPolicy.java",
)
ALLOWED_CONSUMER_SUFFIXES = ("Consumer.java", "TaskConsumer.java")
BANNED_IMPORT = re.compile(
    r"^import\s+(?:static\s+)?("
    r"org\.springframework\.data\.redis\."
    r"|org\.springframework\.kafka\."
    r"|org\.apache\.kafka\."
    r"|org\.springframework\.jdbc\."
    r"|javax\.sql\."
    r")"
)


def is_business_layer(path: Path) -> bool:
    relative = "/" + path.relative_to(ROOT).as_posix()
    if MAIN_JAVA not in relative:
        return False
    if not any(layer in relative for layer in BUSINESS_LAYERS):
        return False
    return not any(owner in relative for owner in ALLOWED_OWNERS)


def is_allowed_operational_exception(path: Path) -> bool:
    name = path.name
    return name in ALLOWED_OPERATIONAL_FILES or any(
        name.endswith(suffix) for suffix in ALLOWED_CONSUMER_SUFFIXES
    )


def scan_file(path: Path) -> list[str]:
    if not is_business_layer(path) or is_allowed_operational_exception(path):
        return []
    errors: list[str] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if BANNED_IMPORT.search(stripped):
            errors.append(f"{path.relative_to(ROOT)}:{line_number}: {stripped}")
    return errors


def main() -> int:
    errors: list[str] = []
    for path in ROOT.glob("**/src/main/java/**/*.java"):
        errors.extend(scan_file(path))
    if errors:
        print("❌ Direct infrastructure client boundary check failed:")
        print(
            "Application/domain business code must depend on ports, not Redis/Kafka/JDBC clients."
        )
        print(
            "Tests, infrastructure/config/support, Kafka consumer entries, and ops lag probes are excluded."
        )
        for error in errors:
            print(f"  - {error}")
        return 1
    print("✅ Direct infrastructure client boundary passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
