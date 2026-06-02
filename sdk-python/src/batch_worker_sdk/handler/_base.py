"""LOCAL STUB — do NOT commit.

This file is a temporary placeholder used to let the
``feature/sdk-python-handler-typed`` branch compile/test locally while
the concurrent ``feature/sdk-python-handler-abstract-base`` branch is
still landing the real abstract base classes
(``SdkAbstractTaskHandler``, ``SdkRowResult``, etc).

The real ``_base.py`` (provided by the other branch) will replace this
file once both branches merge. This stub MUST be left out of git
(see .gitignore entry added in the same commit-less working dir).
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from collections import OrderedDict
from typing import Any

from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult

_log = logging.getLogger(__name__)


class SdkAbstractTaskHandler(ABC):
    """Mirror of Java ``SdkAbstractTaskHandler`` (template-method base)."""

    @abstractmethod
    def task_type(self) -> str: ...

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        return None

    def validate(self, ctx: SdkTaskContext) -> None:
        return None

    def before(self, ctx: SdkTaskContext) -> None:
        return None

    @abstractmethod
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult: ...

    def after(self, ctx: SdkTaskContext, result: SdkTaskResult) -> None:
        return None

    def cleanup(self, ctx: SdkTaskContext) -> None:
        return None

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        started = False
        try:
            self.validate(ctx)
            self.before(ctx)
            started = True
            r = await self._do_execute(ctx)
            self.after(ctx, r)
            return (
                r
                if r is not None
                else SdkTaskResult.fail(
                    "HANDLER_NULL_RESULT", "handler returned null SdkTaskResult"
                )
            )
        except Exception as t:  # noqa: BLE001
            _log.exception(
                "SDK handler %s failed (taskType=%s)", type(self).__name__, self.task_type()
            )
            msg = str(t) or type(t).__name__
            return SdkTaskResult.fail("HANDLER_EXCEPTION", msg, cause=t)
        finally:
            if started:
                try:
                    self.cleanup(ctx)
                except Exception:  # noqa: BLE001
                    _log.warning(
                        "SDK handler %s cleanup() failed", type(self).__name__, exc_info=True
                    )


class SdkRowResult:
    """Mirror of Java ``SdkRowResult`` — row-level counter."""

    def __init__(self) -> None:
        self._success = 0
        self._skipped = 0
        self._failed = 0
        self._reject = 0

    def inc_success(self) -> None:
        self._success += 1

    def inc_skipped(self) -> None:
        self._skipped += 1

    def inc_failed(self) -> None:
        self._failed += 1

    def inc_reject(self) -> None:
        self._reject += 1

    def add_success(self, n: int) -> None:
        self._success += n

    @property
    def success(self) -> int:
        return self._success

    @property
    def skipped(self) -> int:
        return self._skipped

    @property
    def failed(self) -> int:
        return self._failed

    @property
    def reject(self) -> int:
        return self._reject

    @property
    def total(self) -> int:
        return self._success + self._skipped + self._failed + self._reject

    def to_output(self) -> dict[str, Any]:
        m: OrderedDict[str, Any] = OrderedDict()
        m["success"] = self._success
        if self._skipped > 0:
            m["skipped"] = self._skipped
        if self._failed > 0:
            m["failed"] = self._failed
        if self._reject > 0:
            m["reject"] = self._reject
        m["total"] = self.total
        return dict(m)
