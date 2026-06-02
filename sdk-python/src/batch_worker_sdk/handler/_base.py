"""Abstract task-handler base + per-row counter (ADR-036).

Python equivalent of Java ``SdkAbstractTaskHandler`` and ``SdkRowResult``
(``com.example.batch.sdk.handler``). The Java SDK exposes a synchronous
``execute`` template method that locks the execution order
``validate -> before -> doExecute -> after + finally cleanup`` so tenant
code is restricted to filling protected hooks. The Python form preserves
the same template-method shape but runs ``execute`` as ``async def`` to
fit the SDK's async-only contract (``pyproject.toml`` declares
``Framework :: AsyncIO``).

The base intentionally satisfies the ``SdkTaskHandler`` structural
``Protocol`` (``handler.py``) — subclasses still pass ``isinstance(h,
SdkTaskHandler)`` checks without inheriting from ``Protocol`` directly.
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, final

from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult

logger = logging.getLogger(__name__)

# Error codes written into ``SdkTaskResult.output['errorCode']`` for the
# template-method failure paths. Kept short + stable so the platform's
# atomic-lane taxonomy can route on them.
HANDLER_ERROR_CODE: str = "HANDLER_ERROR"
INVALID_PARAMS_CODE: str = "INVALID_PARAMS"
CANCELLED_CODE: str = "CANCELLED"
NULL_RESULT_CODE: str = "NULL_RESULT"


@dataclass
class SdkRowResult:
    """Row-level counters for the 4 long-running handler shapes
    (Import / Export / Process / Dispatch).

    Mirror of Java ``SdkRowResult``: success / skipped / failed / reject
    counters plus :meth:`to_output` which renders the non-zero entries
    (plus ``success`` and ``total``) into the
    :attr:`SdkTaskResult.output` map the platform's REPORT call forwards.

    The Java class uses ``LongAdder`` for thread-safe concurrent
    accumulation; the Python SDK is async-single-threaded per task so a
    plain ``int`` is sufficient (the same task's handler cannot be
    racing itself across threads). Handlers that fan out to thread/
    process pools must serialize their own writes back to the counter.
    """

    success_count: int = field(default=0)
    skipped_count: int = field(default=0)
    failed_count: int = field(default=0)
    reject_count: int = field(default=0)

    def inc_success(self) -> None:
        self.success_count += 1

    def inc_skipped(self) -> None:
        self.skipped_count += 1

    def inc_failed(self) -> None:
        self.failed_count += 1

    def inc_reject(self) -> None:
        self.reject_count += 1

    def add_success(self, n: int) -> None:
        self.success_count += n

    def success(self) -> int:
        return self.success_count

    def skipped(self) -> int:
        return self.skipped_count

    def failed(self) -> int:
        return self.failed_count

    def reject(self) -> int:
        return self.reject_count

    def total(self) -> int:
        """Processed-rows total: success + skipped + failed + reject."""
        return (
            self.success_count
            + self.skipped_count
            + self.failed_count
            + self.reject_count
        )

    def to_output(self) -> dict[str, Any]:
        """Render the non-zero counters into an ``output``-shaped dict.

        Aligns with Java ``SdkRowResult.toOutput()`` — ``success`` and
        ``total`` are always present; the others appear only when > 0
        so the wire payload stays compact.
        """
        out: dict[str, Any] = {"success": self.success()}
        if self.skipped() > 0:
            out["skipped"] = self.skipped()
        if self.failed() > 0:
            out["failed"] = self.failed()
        if self.reject() > 0:
            out["reject"] = self.reject()
        out["total"] = self.total()
        return out


class SdkAbstractTaskHandler(ABC):
    """Template-method base class for tenant handlers (ADR-036).

    Mirror of Java ``SdkAbstractTaskHandler``. Subclasses fill the
    protected hooks; :meth:`execute` is :func:`typing.final` and locks
    the execution order::

        _validate -> _before -> _do_execute -> _after
        (finally) _cleanup  -- only when _before ran

    All thrown exceptions inside the hooks are caught and converted to
    :meth:`SdkTaskResult.fail`. Cooperative cancellation
    (:attr:`SdkTaskContext.cancel_signal`) is checked once before
    running ``_do_execute``; long-running shapes poll the signal in
    their own loops.

    Structurally satisfies the :class:`SdkTaskHandler` ``Protocol``
    declared in ``handler.py``.
    """

    @abstractmethod
    def task_type(self) -> str:
        """Globally unique task-type code (aligns with Java ``taskType()``)."""

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        """Optional custom-task-type descriptor; default ``None``."""
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        """Optional cooperative-cancel hook; default no-op.

        Aligns with the :class:`SdkTaskHandler` Protocol — most handlers
        poll :attr:`SdkTaskContext.cancel_signal` instead of overriding
        this.
        """
        return None

    @final
    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        """Template-method entrypoint. **Final** — do not override.

        Subclasses override :meth:`_do_execute` (and optionally the
        ``_validate`` / ``_before`` / ``_after`` / ``_cleanup`` hooks).
        Aligns 1:1 with Java ``SdkAbstractTaskHandler.execute``.
        """
        started = False
        try:
            await self._validate(ctx)
            await self._before(ctx)
            started = True
            if (
                ctx.cancel_signal is not None
                and ctx.cancel_signal.is_cancellation_requested
            ):
                return SdkTaskResult.fail(
                    CANCELLED_CODE,
                    f"task cancelled before execution (taskId={ctx.task_id})",
                )
            result = await self._do_execute(ctx)
            # Java semantics: handler returning null is converted to fail
            # (subclasses may bypass the type check via type: ignore tricks
            # or raw None returns; harden the gate here).
            if result is None:
                return SdkTaskResult.fail(  # type: ignore[unreachable]
                    NULL_RESULT_CODE,
                    "handler returned null SdkTaskResult",
                )
            await self._after(ctx, result)
            return result
        except Exception as t:
            logger.exception(
                "SDK handler %s failed (taskType=%s, taskId=%s): %s",
                type(self).__name__,
                self._safe_task_type(),
                getattr(ctx, "task_id", None),
                t,
            )
            return SdkTaskResult.fail(
                HANDLER_ERROR_CODE,
                str(t) or type(t).__name__,
                cause=t,
            )
        finally:
            if started:
                try:
                    await self._cleanup(ctx)
                except Exception as cleanup_ex:
                    logger.warning(
                        "SDK handler %s cleanup() failed: %s",
                        type(self).__name__,
                        cleanup_ex,
                    )

    # ---- Protected hooks (override in subclasses or shape bases) ----

    async def _validate(self, ctx: SdkTaskContext) -> None:
        """Business input validation. Raise to fail. Default no-op."""
        return None

    async def _before(self, ctx: SdkTaskContext) -> None:
        """Resource acquire (open conn / lease). Default no-op."""
        return None

    @abstractmethod
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        """Real business work. Implemented by shape bases or final subclasses."""

    async def _after(self, ctx: SdkTaskContext, result: SdkTaskResult) -> None:
        """Post-success hook (skipped on exception). Default no-op."""
        return None

    async def _cleanup(self, ctx: SdkTaskContext) -> None:
        """``finally`` release hook. Default no-op."""
        return None

    # ---- Internal helpers ----

    def _safe_task_type(self) -> str:
        """Don't let a broken ``task_type()`` mask the original error."""
        try:
            return self.task_type()
        except Exception:
            return "<task_type() raised>"


__all__ = [
    "CANCELLED_CODE",
    "HANDLER_ERROR_CODE",
    "INVALID_PARAMS_CODE",
    "NULL_RESULT_CODE",
    "SdkAbstractTaskHandler",
    "SdkRowResult",
]
