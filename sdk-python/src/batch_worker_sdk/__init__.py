"""batch-worker-sdk: Python SDK for the file-batch-system worker protocol.

Phase 0 status: scaffolding only. No runtime functionality is exported yet —
the HTTP client, Kafka consumer, scheduler, and handler runtime land in
Phase 1+ (see ``README.md`` for the roadmap).

The public surface here intentionally mirrors what the Java SDK
(``batch-worker-sdk/``) exposes so users can mentally translate between
the two. Concrete classes (``WorkerClient``, ``HandlerContext``,
``WorkerConfig``) will be added in P1.
"""

from __future__ import annotations

from batch_worker_sdk._version import __version__

__all__: list[str] = [
    "__version__",
]
