"""batch-worker-sdk: Python SDK for the file-batch-system worker protocol.

Phase 1 status: HTTP client + retry/backoff + config validation (Lane Q).
P0.5 (Lane R) added the public **API surface** mirroring the Java SDK's
contracts. P3 (Lane T, this PR) added :class:`BatchPlatformClient`,
the heartbeat / lease schedulers, and heartbeat-directive parsing.
Kafka consumer (P2, Lane S), FSM + cancel (P4, Lane U), and testkit
(P5, Lane V) land in subsequent lanes. See ``README.md`` Roadmap.

Public surface:

- :class:`BatchPlatformClientConfig` — async equivalent of Java
  ``BatchPlatformClientConfig``. Construct directly or via
  ``BatchPlatformClientConfig.from_env()``.
- Exception hierarchy (:class:`PlatformError` and four typed subclasses)
  matching wire-protocol §B classification.
- API surface stubs mirroring Java SDK contracts: :class:`SdkTaskHandler`,
  :class:`SdkTaskContext`, :class:`SdkTaskResult`, :class:`WorkerRuntimeState`,
  :class:`ProgressReporter`, :class:`CancellationSignal`,
  :class:`SdkTaskTypeDescriptor`. Implementations land in P1-P5.

Intentionally **not** exported:

- ``_http`` / ``_retry``: leading underscore = package-internal. Once
  the higher-level ``BatchPlatformClient`` lands (P3) callers should
  not need to touch raw HTTP plumbing.
"""

from __future__ import annotations

from batch_worker_sdk._version import __version__
from batch_worker_sdk.cancellation import CancellationSignal
from batch_worker_sdk.client import BatchPlatformClient
from batch_worker_sdk.config import BatchPlatformClientConfig
from batch_worker_sdk.context import SdkTaskContext
from batch_worker_sdk.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.dispatcher import TaskDispatcher
from batch_worker_sdk.exceptions import (
    AuthError,
    ConflictError,
    PersistentClientError,
    PlatformError,
    TransientError,
)
from batch_worker_sdk.handler import SdkTaskHandler
from batch_worker_sdk.progress import ProgressReporter
from batch_worker_sdk.result import SdkTaskResult
from batch_worker_sdk.state import WorkerRuntimeState

__all__: list[str] = [
    "AuthError",
    "BatchPlatformClient",
    "BatchPlatformClientConfig",
    "CancellationSignal",
    "ConflictError",
    "PersistentClientError",
    "PlatformError",
    "ProgressReporter",
    "SdkTaskContext",
    "SdkTaskHandler",
    "SdkTaskResult",
    "SdkTaskTypeDescriptor",
    "TaskDispatcher",
    "TransientError",
    "WorkerRuntimeState",
    "__version__",
]
