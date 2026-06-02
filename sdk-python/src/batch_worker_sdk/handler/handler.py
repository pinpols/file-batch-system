"""Tenant-implemented task handler contract (P0.5 stub).

Mirrors Java ``com.example.batch.sdk.task.SdkTaskHandler``. The Python
form is an :class:`~typing.Protocol` rather than an :class:`abc.ABC`:
runtime-checkable structural typing fits async handlers better and
removes the inheritance ceremony Java needs but Python doesn't.

Phase 1+ adds an abstract base class with retry/idempotency hooks; P0.5
just nails down the public shape so downstream lanes can import the
type without a circular dep.
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult


@runtime_checkable
class SdkTaskHandler(Protocol):
    """Implement this on each task type your worker runs.

    A single Python worker process can register many handlers; the SDK
    dispatch loop routes by :meth:`task_type`.

    Typical usage (Phase 1+ once :class:`WorkerClient` exists)::

        class MyImportHandler:
            def task_type(self) -> str:
                return "tenant_xyz_import"

            async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
                rows = await load_rows(ctx.parameters["source"])
                return SdkTaskResult.success_with(
                    output={"rows": len(rows)},
                    message=f"imported {len(rows)} rows",
                )
    """

    def task_type(self) -> str:
        """Globally unique task-type code (matches ``job_definition.job_type``)."""
        ...

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        """Run the task. ``ctx`` is framework-supplied; return the result."""
        ...

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        """Optional — declare a custom task-type descriptor (defaults to ``None``).

        Returning a descriptor causes it to be sent on worker-register;
        the platform upserts it into ``custom_task_type_registry`` and
        the console renders forms from the embedded JSON Schema.
        """
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        """Optional — cooperative cancel hook (default no-op).

        Called by the SDK when the platform signals cancellation. Most
        handlers ignore this and rely on
        :attr:`SdkTaskContext.is_dry_run`-style polling instead; override
        only if you need synchronous cleanup.
        """
        return None
