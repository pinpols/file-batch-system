"""Tests for ``BatchPlatformClientConfig`` — required fields, env factory,
and the four cross-field timing rules ported from Java Lane I."""

from __future__ import annotations

from datetime import timedelta

import pytest
from pydantic import ValidationError

from batch_worker_sdk import BatchPlatformClientConfig

_MIN_KWARGS = {
    "base_url": "http://orch:8081",
    "tenant_id": "acme",
    "worker_code": "w-1",
}


def test_minimal_config_defaults():
    cfg = BatchPlatformClientConfig(**_MIN_KWARGS)
    assert cfg.tenant_id == "acme"
    assert cfg.heartbeat_interval == timedelta(seconds=30)
    assert cfg.lease_renew_interval == timedelta(seconds=60)
    assert cfg.http_timeout == timedelta(seconds=10)
    assert cfg.retry_max_attempts == 3
    assert cfg.retry_base_delay == timedelta(milliseconds=200)


def test_base_url_must_not_end_with_slash():
    with pytest.raises(ValueError, match="must not end with '/'"):
        BatchPlatformClientConfig(
            base_url="http://orch:8081/",
            tenant_id="acme",
            worker_code="w-1",
        )


def test_base_url_must_have_scheme():
    with pytest.raises(ValueError, match="must start with"):
        BatchPlatformClientConfig(base_url="orch:8081", tenant_id="acme", worker_code="w-1")


def test_heartbeat_below_1s_rejected():
    with pytest.raises(ValueError, match="heartbeat_interval"):
        BatchPlatformClientConfig(
            **_MIN_KWARGS,
            heartbeat_interval=timedelta(milliseconds=500),
            lease_renew_interval=timedelta(seconds=5),
            http_timeout=timedelta(milliseconds=200),
        )


def test_lease_below_5s_rejected():
    with pytest.raises(ValueError, match="lease_renew_interval"):
        BatchPlatformClientConfig(**_MIN_KWARGS, lease_renew_interval=timedelta(seconds=4))


def test_lease_exceeding_3x_heartbeat_rejected():
    # hb=10s, lease=31s -> lease > 3*hb=30s
    with pytest.raises(ValueError, match=r"heartbeat_interval \* 3"):
        BatchPlatformClientConfig(
            **_MIN_KWARGS,
            heartbeat_interval=timedelta(seconds=10),
            lease_renew_interval=timedelta(seconds=31),
            http_timeout=timedelta(seconds=4),
        )


def test_http_timeout_exceeding_half_heartbeat_rejected():
    # hb=10s → http must be <= 5s
    with pytest.raises(ValueError, match="heartbeat_interval / 2"):
        BatchPlatformClientConfig(
            **_MIN_KWARGS,
            heartbeat_interval=timedelta(seconds=10),
            lease_renew_interval=timedelta(seconds=20),
            http_timeout=timedelta(seconds=6),
        )


def test_max_concurrent_out_of_range():
    with pytest.raises(ValueError, match="max_concurrent_tasks"):
        BatchPlatformClientConfig(**_MIN_KWARGS, max_concurrent_tasks=0)
    with pytest.raises(ValueError, match="max_concurrent_tasks"):
        BatchPlatformClientConfig(**_MIN_KWARGS, max_concurrent_tasks=65)


def test_from_env_reports_all_missing_at_once():
    with pytest.raises(ValueError, match=r"BATCH_SDK_BASE_URL.*BATCH_SDK_TENANT_ID"):
        BatchPlatformClientConfig.from_env(getter=lambda _key: None)


def test_from_env_happy_path():
    env = {
        "BATCH_SDK_BASE_URL": "http://orch:8081",
        "BATCH_SDK_TENANT_ID": "acme",
        "BATCH_SDK_WORKER_CODE": "w-1",
        "BATCH_SDK_API_KEY": "sekret",
        "BATCH_SDK_HEARTBEAT_INTERVAL_SECONDS": "20",
        "BATCH_SDK_LEASE_RENEW_INTERVAL_SECONDS": "40",
        "BATCH_SDK_HTTP_TIMEOUT_SECONDS": "5",
        "BATCH_SDK_RETRY_BASE_DELAY_MS": "100",
    }
    cfg = BatchPlatformClientConfig.from_env(getter=env.get)
    assert cfg.api_key == "sekret"
    assert cfg.heartbeat_interval == timedelta(seconds=20)
    assert cfg.lease_renew_interval == timedelta(seconds=40)
    assert cfg.retry_base_delay == timedelta(milliseconds=100)


def test_frozen_model_rejects_mutation():
    cfg = BatchPlatformClientConfig(**_MIN_KWARGS)
    with pytest.raises(ValidationError, match="frozen"):
        cfg.tenant_id = "evil"  # type: ignore[misc]
