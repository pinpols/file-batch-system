"""BatchPlatformClientConfig — Python (pydantic v2) port.

Mirrors ``com.example.batch.sdk.client.BatchPlatformClientConfig`` field
set + startup validation. Java SDK uses Lombok ``@Value`` + ``@Builder``;
we use ``pydantic.BaseModel`` with ``model_config = ConfigDict(frozen=True)``
to keep the same "immutable value object" semantics.

Java equivalence table (P1 subset; Kafka SASL fields land in P2):

==============================  ==============================
Java field                      Python field
==============================  ==============================
baseUrl                         base_url
apiKey                          api_key
tenantId                        tenant_id
workerCode                      worker_code
buildId                         build_id
httpTimeout                     http_timeout
heartbeatInterval               heartbeat_interval
leaseRenewInterval              lease_renew_interval
maxConcurrentTasks              max_concurrent_tasks
claimMax5xxRetries              claim_max_5xx_retries
claimRetryBaseDelay             claim_retry_base_delay
clientErrorFailFastThreshold    client_error_fail_fast_threshold
==============================  ==============================

Startup timing validation (``_validate_timings``) ports Java Lane I
PR #251 ``validateTimings()`` byte-for-byte: same four rules, same
suggested-value hints in the error messages so operators can grep both
codebases with the same string.
"""

from __future__ import annotations

import os
from collections.abc import Callable
from datetime import timedelta
from typing import Any

from pydantic import BaseModel, ConfigDict, Field, model_validator

from batch_worker_sdk._version import __version__


class BatchPlatformClientConfig(BaseModel):
    """SDK connection config — async equivalent of the Java value object.

    Construct via ``BatchPlatformClientConfig(base_url=..., tenant_id=...,
    worker_code=...)`` or ``BatchPlatformClientConfig.from_env()``.

    Validation runs automatically (pydantic ``model_validator(mode="after")``)
    so any timing rule violation raises ``ValueError`` at construction.
    """

    model_config = ConfigDict(
        frozen=True,
        str_strip_whitespace=True,
        extra="forbid",
    )

    # ─── required ──────────────────────────────────────────────────────
    base_url: str
    tenant_id: str = Field(min_length=1)
    worker_code: str = Field(min_length=1)

    # ─── optional / defaulted (aligned with Java) ──────────────────────
    api_key: str | None = None
    build_id: str | None = None
    sdk_version: str = __version__

    http_timeout: timedelta = timedelta(seconds=10)
    heartbeat_interval: timedelta = timedelta(seconds=30)
    lease_renew_interval: timedelta = timedelta(seconds=60)

    max_concurrent_tasks: int = Field(default=4, ge=1, le=64)

    # ─── retry knobs (wire-protocol §C) ────────────────────────────────
    retry_max_attempts: int = Field(default=3, ge=1, le=10)
    retry_base_delay: timedelta = timedelta(milliseconds=200)
    client_error_fail_fast_threshold: int = Field(default=5, ge=0, le=100)

    # ─── validation ────────────────────────────────────────────────────

    @model_validator(mode="after")
    def _validate(self) -> BatchPlatformClientConfig:
        if self.base_url.endswith("/"):
            raise ValueError(f"base_url must not end with '/': {self.base_url!r}")
        if not self.base_url.startswith(("http://", "https://")):
            raise ValueError(f"base_url must start with http:// or https://: {self.base_url!r}")
        self._validate_timings()
        return self

    def _validate_timings(self) -> None:
        """Port of Java ``validateTimings()`` (Lane I PR #251).

        Four rules, same error-message shape so operators can grep across
        both stacks:

        - ``heartbeat_interval >= 1s``
        - ``lease_renew_interval >= 5s``
        - ``lease_renew_interval <= heartbeat_interval * 3``
        - ``http_timeout <= heartbeat_interval / 2``
        """
        hb_ms = _ms(self.heartbeat_interval)
        lease_ms = _ms(self.lease_renew_interval)
        http_ms = _ms(self.http_timeout)

        if hb_ms < 1_000:
            raise ValueError(
                f"BatchPlatformClient config invalid: heartbeat_interval="
                f"{self.heartbeat_interval} must be >= 1s (current "
                f"{hb_ms}ms; suggest >= 5s for prod)"
            )
        if lease_ms < 5_000:
            raise ValueError(
                f"BatchPlatformClient config invalid: lease_renew_interval="
                f"{self.lease_renew_interval} must be >= 5s (current "
                f"{lease_ms}ms; suggest 30s..60s for prod)"
            )
        lease_upper = hb_ms * 3
        if lease_ms > lease_upper:
            raise ValueError(
                f"BatchPlatformClient config invalid: lease_renew_interval="
                f"{self.lease_renew_interval} must be <= heartbeat_interval * 3 "
                f"({lease_upper}ms) — 否则 in-flight task 可能被 orch 误判租约过期回收;"
                f"suggest lease_renew_interval approx 2 * heartbeat_interval"
            )
        http_upper = hb_ms // 2
        if http_ms > http_upper:
            raise ValueError(
                f"BatchPlatformClient config invalid: http_timeout="
                f"{self.http_timeout} must be <= heartbeat_interval / 2 "
                f"({http_upper}ms) — 否则心跳超时会堆积阻塞 scheduler;"
                f"suggest 调大 heartbeat_interval 或调小 http_timeout"
            )

    # ─── env-driven factory ────────────────────────────────────────────

    @classmethod
    def from_env(
        cls,
        prefix: str = "BATCH_SDK_",
        getter: Callable[[str], str | None] = os.environ.get,
    ) -> BatchPlatformClientConfig:
        """Construct from environment variables.

        Required: ``<prefix>BASE_URL / TENANT_ID / WORKER_CODE``.
        Optional: ``API_KEY / BUILD_ID / HTTP_TIMEOUT_SECONDS /
        HEARTBEAT_INTERVAL_SECONDS / LEASE_RENEW_INTERVAL_SECONDS /
        MAX_CONCURRENT_TASKS / RETRY_MAX_ATTEMPTS / RETRY_BASE_DELAY_MS /
        CLIENT_ERROR_FAIL_FAST_THRESHOLD``.

        Kafka-related env vars are reserved for P2 (Kafka consumer lane).
        """
        missing: list[str] = []

        def need(key: str) -> str:
            v = getter(prefix + key)
            if v is None or not v.strip():
                missing.append(prefix + key)
                return ""
            return v.strip()

        base_url = need("BASE_URL")
        tenant_id = need("TENANT_ID")
        worker_code = need("WORKER_CODE")
        if missing:
            raise ValueError("missing required env vars: " + ", ".join(missing))

        kwargs: dict[str, Any] = {
            "base_url": base_url,
            "tenant_id": tenant_id,
            "worker_code": worker_code,
        }
        opt_str = {
            "api_key": "API_KEY",
            "build_id": "BUILD_ID",
        }
        for field, env_key in opt_str.items():
            v = getter(prefix + env_key)
            if v is not None and v.strip():
                kwargs[field] = v.strip()

        opt_int = {
            "max_concurrent_tasks": "MAX_CONCURRENT_TASKS",
            "retry_max_attempts": "RETRY_MAX_ATTEMPTS",
            "client_error_fail_fast_threshold": "CLIENT_ERROR_FAIL_FAST_THRESHOLD",
        }
        for field, env_key in opt_int.items():
            v = getter(prefix + env_key)
            if v is not None and v.strip():
                kwargs[field] = int(v.strip())

        opt_seconds = {
            "http_timeout": "HTTP_TIMEOUT_SECONDS",
            "heartbeat_interval": "HEARTBEAT_INTERVAL_SECONDS",
            "lease_renew_interval": "LEASE_RENEW_INTERVAL_SECONDS",
        }
        for field, env_key in opt_seconds.items():
            v = getter(prefix + env_key)
            if v is not None and v.strip():
                kwargs[field] = timedelta(seconds=int(v.strip()))

        v_ms = getter(prefix + "RETRY_BASE_DELAY_MS")
        if v_ms is not None and v_ms.strip():
            kwargs["retry_base_delay"] = timedelta(milliseconds=int(v_ms.strip()))

        return cls(**kwargs)


def _ms(td: timedelta) -> int:
    """``timedelta`` → integer milliseconds (matches Java ``Duration.toMillis()``)."""
    return int(td.total_seconds() * 1000)
