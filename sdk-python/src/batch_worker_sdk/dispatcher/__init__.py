"""Dispatcher subpackage — mirror of Java ``com.example.batch.sdk.dispatcher``.

Hosts :class:`TaskDispatcher` (per-tenant in-flight registry + handler
routing) and the :func:`run_worker` runner entrypoint.
"""

from __future__ import annotations

from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher

__all__: list[str] = [
    "TaskDispatcher",
]
