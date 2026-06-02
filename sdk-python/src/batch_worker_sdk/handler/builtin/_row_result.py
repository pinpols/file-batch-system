"""Row-level counter for builtin handlers.

Mirrors Java ``com.example.batch.sdk.handler.SdkRowResult`` — success /
skipped / failed / reject counts that lower into ``SdkTaskResult.output``
via :meth:`to_output`. Used by the builtin Import / Dispatch / Export
handlers; lives under :mod:`handler.builtin` for now because the broader
abstract-base lane (which owns the public ``SdkRowResult`` mirror in
Python) is still in flight. Once that lane lands, this module re-exports
from the canonical location and is removed.
"""

from __future__ import annotations

from typing import Any


class SdkRowResult:
    """Mutable row counter — single-threaded use (async handlers are single-threaded per task).

    Java uses :class:`LongAdder` for multi-thread accumulation; the Python
    SDK runs each task on a single asyncio task, so plain ``int`` suffices.
    """

    __slots__ = ("_failed", "_reject", "_skipped", "_success")

    def __init__(self) -> None:
        self._success = 0
        self._skipped = 0
        self._failed = 0
        self._reject = 0

    def inc_success(self, n: int = 1) -> None:
        self._success += n

    def inc_skipped(self, n: int = 1) -> None:
        self._skipped += n

    def inc_failed(self, n: int = 1) -> None:
        self._failed += n

    def inc_reject(self, n: int = 1) -> None:
        self._reject += n

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
        """Convert to ``SdkTaskResult.output`` map (omits zero counts other than success)."""
        out: dict[str, Any] = {"success": self._success}
        if self._skipped:
            out["skipped"] = self._skipped
        if self._failed:
            out["failed"] = self._failed
        if self._reject:
            out["reject"] = self._reject
        out["total"] = self.total
        return out
