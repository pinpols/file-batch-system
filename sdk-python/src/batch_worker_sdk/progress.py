"""Per-task progress / checkpoint slot (P0.5 stub).

Mirrors Java ``com.example.batch.sdk.task.ProgressReporter``. Latest-
value-wins semantics: a handler updating progress in a tight loop
overwrites the previous snapshot; the lease-renewal tick samples
:meth:`latest` and forwards it as the ``details`` field on the renew
request body. P0.5 leaves the actual report path as
``NotImplementedError`` — P4 will wire it up.
"""

from __future__ import annotations

from typing import Any


class ProgressReporter:
    """Async-safe single-slot progress holder.

    .. warning::
       Sensitive credentials must **not** be passed in ``details`` — the
       value is persisted by the platform and visible to operators.
    """

    def report(self, details: dict[str, Any]) -> None:
        """Write the latest progress snapshot.

        P0.5 raises :class:`NotImplementedError`; P4 stores an immutable
        copy and makes it visible to the lease-renewal task.
        """
        raise NotImplementedError("P4 implements ProgressReporter.report")

    def latest(self) -> dict[str, Any] | None:
        """Return the most recent snapshot, or ``None`` if never reported.

        P0.5 raises :class:`NotImplementedError`; P4 returns the stored
        snapshot.
        """
        raise NotImplementedError("P4 implements ProgressReporter.latest")
