"""Worker runtime state machine (P0.5 stub).

Mirrors Java ``com.example.batch.sdk.dispatcher.WorkerRuntimeState``
(see ``batch-worker-sdk/src/main/java/com/example/batch/sdk/dispatcher/WorkerRuntimeState.java``).
Phase 2 §2.4: state machine driven by the platform directive returned
on heartbeat responses. Implementation that *actually* mutates state in
response to heartbeats lands in P2; this module only declares the enum
so the public surface is stable.
"""

from __future__ import annotations

from enum import StrEnum


class WorkerRuntimeState(StrEnum):
    """Runtime states a worker can be in (string-valued for wire equivalence)."""

    NORMAL = "NORMAL"
    """Normal: accepts dispatches, runs tasks as usual."""

    DEGRADED = "DEGRADED"
    """Degraded: platform hint to lower concurrency; still accepts dispatches."""

    PAUSED = "PAUSED"
    """Paused: stop claiming new tasks (resumable); in-flight tasks drain."""

    DRAINING = "DRAINING"
    """Draining: stop claiming new tasks (terminal); typically precedes shutdown."""

    def accepts_new_tasks(self) -> bool:
        """Whether this state accepts new task claims.

        ``PAUSED`` / ``DRAINING`` reject; ``NORMAL`` / ``DEGRADED`` accept.
        Aligns with Java ``acceptsNewTasks()``.
        """
        return self in (WorkerRuntimeState.NORMAL, WorkerRuntimeState.DEGRADED)
