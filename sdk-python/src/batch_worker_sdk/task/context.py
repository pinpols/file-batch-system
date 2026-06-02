"""Task execution context (P0.5 stub).

Mirrors Java ``com.example.batch.sdk.task.SdkTaskContext`` — the value
record handed to :meth:`SdkTaskHandler.execute`. The Python shape is a
flattened, Pythonic projection of the Java 9-arg constructor: the
scheduling-context sub-record is inlined as top-level fields
(``biz_date`` / ``attempt_no`` / ``trigger_code`` / ``workflow_run_id``
/ ``is_holiday``) so handlers read scheduling facts directly without
walking a nested object.

Field naming follows PEP 8 (``tenant_id`` not ``tenantId``). The wire
adapter that materializes this from the dispatch payload (P1+) handles
the camel-case → snake_case mapping.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field

from batch_worker_sdk.task.cancellation import CancellationSignal
from batch_worker_sdk.task.progress import ProgressReporter


class SdkTaskContext(BaseModel):
    """Immutable execution context for a single task instance.

    Aligns with Java ``SdkTaskContext`` (7-arg legacy + scheduling
    extension). ``runtime_attributes`` is the open-ended escape hatch
    the platform uses to inject traceId, pipeline-instance id, dry-run
    flag, and similar.
    """

    model_config = ConfigDict(frozen=True, extra="forbid", arbitrary_types_allowed=True)

    tenant_id: str
    """Owning tenant id (required, aligns with ``tenantId``)."""

    task_id: int
    """Orchestrator-side task primary key (aligns with ``taskId``)."""

    worker_code: str
    """Identifier of this worker (aligns with ``workerId``)."""

    task_type: str
    """Task-type code routing this dispatch to the right handler."""

    parameters: dict[str, Any] = Field(default_factory=dict)
    """User-defined task parameters from ``job_definition.parameters``."""

    biz_date: str | None = None
    """Business date (ISO ``YYYY-MM-DD``); ``None`` when no scheduling ctx."""

    prev_biz_date: str | None = None
    """Previous business date (ISO ``YYYY-MM-DD``); ``None`` outside scheduling."""

    next_biz_date: str | None = None
    """Next business date (ISO ``YYYY-MM-DD``); ``None`` outside scheduling."""

    is_holiday: bool | None = None
    """Holiday/weekend flag for ``biz_date``; ``None`` outside scheduling."""

    attempt_no: int = 1
    """Execution attempt counter (1-based; increments on retry/reclaim)."""

    trigger_code: str | None = None
    """Source trigger code (currently always ``None`` — column not yet wired)."""

    workflow_run_id: int | None = None
    """Owning workflow run id; ``None`` for non-workflow direct dispatches."""

    runtime_attributes: dict[str, Any] = Field(default_factory=dict)
    """Open-ended platform-injected attributes (traceId / dryRun / etc.)."""

    # P4-injected runtime collaborators. Not part of the wire payload —
    # the dispatcher attaches them when materializing the context for a
    # specific task instance. Frozen=True still applies (the references
    # cannot be reassigned after construction), but the referenced
    # objects are mutable (CancellationSignal flips its internal event,
    # ProgressReporter writes its snapshot under a lock). ``exclude=True``
    # keeps them out of any ``model_dump()`` output the dispatcher might
    # log.
    cancel_signal: CancellationSignal | None = Field(default=None, exclude=True, repr=False)
    """Cooperative cancellation signal for this task execution (P4).

    Long-running handlers should poll
    ``ctx.cancel_signal.is_cancellation_requested`` (or ``await
    ctx.cancel_signal.wait_cancelled()``) and return early when set,
    instead of waiting for the lease to expire. ``None`` only in
    P0.5-era callers that haven't been upgraded yet.
    """

    progress_reporter: ProgressReporter | None = Field(default=None, exclude=True, repr=False)
    """Latest-value-wins progress slot for this task execution (P4).

    Handlers call ``ctx.progress_reporter.report({...})`` in their long
    loop; the lease-renewal scheduler samples ``latest()`` on each
    tick and includes the snapshot in the renew request body so the
    platform's job-task detail view stays fresh. ``None`` only in
    P0.5-era callers that haven't been upgraded yet.
    """

    def is_dry_run(self) -> bool:
        """Whether the dispatch is a dry-run probe (ADR-026).

        Reads ``runtime_attributes['dryRun']`` first (platform-injected
        path), then falls back to ``parameters['dryRun']`` for handlers
        that opt in via user parameters. Aligns with Java Lane B
        ``TaskContext.isDryRun()``.
        """
        for source in (self.runtime_attributes, self.parameters):
            value = source.get("dryRun")
            if isinstance(value, bool):
                return value
            if isinstance(value, str):
                return value.lower() == "true"
        return False
