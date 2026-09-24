#!/usr/bin/env python3
"""Guard the shared SDK environment-variable contract across language implementations."""

from __future__ import annotations

import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
REQUIRED_FACTORY_KEYS = {
    "BATCH_SDK_BASE_URL",
    "BATCH_SDK_TENANT_ID",
    "BATCH_SDK_WORKER_CODE",
    "BATCH_SDK_KAFKA_BOOTSTRAP",
    "BATCH_SDK_RETRY_MAX_ATTEMPTS",
    "BATCH_SDK_RETRY_BASE_DELAY_MS",
    "BATCH_SDK_STRICT_TIMING",
    "BATCH_SDK_REQUEST_SIGNING_ENABLED",
}
LIVE_TEST_FILES = (
    ROOT / "sdk/python/tests/test_kafka_live_integration.py",
    ROOT / "sdk/typescript/kafka/kafkaConsumer.integration.test.ts",
    ROOT / "sdk/go/kafka/kafka_consumer_integration_test.go",
    ROOT / "sdk/rust/src/kafka.rs",
)


def main() -> int:
    errors: list[str] = []
    java = (ROOT / "sdk/java/core/src/main/java/io/github/pinpols/batch/sdk/client/BatchPlatformClientConfig.java").read_text()
    python = (ROOT / "sdk/python/src/batch_worker_sdk/client/config.py").read_text()
    for key in sorted(REQUIRED_FACTORY_KEYS):
        suffix = key.removeprefix("BATCH_SDK_")
        if suffix not in java:
            errors.append(f"Java fromEnv missing {key}")
        if suffix not in python:
            errors.append(f"Python from_env missing {key}")

    forbidden = (
        'getenv("KAFKA_BOOTSTRAP")',
        "process.env.KAFKA_BOOTSTRAP",
        'Getenv("KAFKA_BOOTSTRAP")',
        'var("KAFKA_BOOTSTRAP")',
    )
    for path in LIVE_TEST_FILES:
        text = path.read_text(encoding="utf-8")
        if "BATCH_SDK_KAFKA_BOOTSTRAP" not in text:
            errors.append(f"{path.relative_to(ROOT)} missing BATCH_SDK_KAFKA_BOOTSTRAP")
        for token in forbidden:
            if token in text:
                errors.append(f"{path.relative_to(ROOT)} uses legacy {token}")

    if errors:
        print("❌ SDK configuration environment parity failed:")
        for error in errors:
            print(f"  - {error}")
        return 1
    print("✅ SDK configuration environment parity passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
