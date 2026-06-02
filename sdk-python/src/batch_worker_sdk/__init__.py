"""batch-worker-sdk: Python SDK for the file-batch-system worker protocol.

Phase 1 status: HTTP client + retry/backoff + config validation. Kafka
consumer (P2), scheduler (P3), FSM + cancel (P4), and testkit (P5) land
in subsequent lanes. See ``README.md`` Roadmap.

Public surface (P1):

- :class:`BatchPlatformClientConfig` — async equivalent of Java
  ``BatchPlatformClientConfig``. Construct directly or via
  ``BatchPlatformClientConfig.from_env()``.
- Exception hierarchy (:class:`PlatformError` and four typed subclasses)
  matching wire-protocol §B classification.

Intentionally **not** exported:

- ``_http`` / ``_retry``: leading underscore = package-internal. Once
  the higher-level ``BatchPlatformClient`` lands (P3) callers should
  not need to touch raw HTTP plumbing.
"""

from __future__ import annotations

from batch_worker_sdk._version import __version__
from batch_worker_sdk.config import BatchPlatformClientConfig
from batch_worker_sdk.exceptions import (
    AuthError,
    ConflictError,
    PersistentClientError,
    PlatformError,
    TransientError,
)

__all__: list[str] = [
    "AuthError",
    "BatchPlatformClientConfig",
    "ConflictError",
    "PersistentClientError",
    "PlatformError",
    "TransientError",
    "__version__",
]
