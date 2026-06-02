"""Per-task progress / checkpoint slot (P4 implementation).

Mirrors Java ``com.example.batch.sdk.task.ProgressReporter`` —
latest-value-wins semantics: a handler updating progress in a tight
loop overwrites the previous snapshot; the lease-renewal tick samples
:meth:`latest` and forwards it as the ``details`` field on the renew
request body.

Concurrency model
-----------------
The handler coroutine writes via :meth:`report`; the lease-renewal task
(running as a separate ``asyncio.Task`` driven by
``LeaseRenewalScheduler``) reads via :meth:`latest`. Even though both
run on the **same** event loop in normal use, the Python SDK promises
async-only at the public-API boundary and the Java equivalent uses
``volatile``; we use a :class:`threading.Lock` (cheap, ~50ns) to keep
the snapshot pointer atomic even if a future executor offloads
heartbeats to a thread.

Defensive copying
-----------------
:meth:`report` makes a **shallow copy** of the input dict so the caller
can mutate the source without surprising the lease-renewal task.
:meth:`latest` returns a **shallow copy** so the consumer can't mutate
the stored snapshot. Nested mutable values (lists, dicts) are not
deep-copied: this matches Java's ``Map.copyOf`` which also doesn't
deep-copy values. Callers who need to mutate nested structures should
build a fresh dict each ``report`` call (the recommended pattern).

Sensitive-data guard
--------------------
``details`` is persisted to ``job_task`` and visible to operators
(see Java ``ProgressReporter`` Javadoc). Before storing we screen the
keys against :mod:`batch_worker_sdk._sensitive_keys` and ``raise
ValueError`` on any match — same intent as Java Lane C's
``SensitiveDataValidator``.
"""

from __future__ import annotations

import threading
from typing import Any

from batch_worker_sdk._sensitive_keys import find_sensitive_keys


class ProgressReporter:
    """Async-safe single-slot progress holder."""

    __slots__ = ("_lock", "_snapshot")

    def __init__(self) -> None:
        self._lock: threading.Lock = threading.Lock()
        self._snapshot: dict[str, Any] | None = None

    def report(self, details: dict[str, Any]) -> None:
        """Write the latest progress snapshot.

        :param details: progress fields (e.g. ``{"processed": 1200,
            "total": 50000, "checkpoint": "row=1200"}``). Must be a
            non-``None`` dict. Sensitive keys (password / secret /
            token / credential / apikey / ...) raise
            :class:`ValueError`.
        :raises ValueError: ``details`` is ``None``, not a dict, or
            contains a key that looks like a credential.

        The stored copy is independent of the caller's dict: callers
        may mutate ``details`` after the call without affecting the
        snapshot, and successive :meth:`latest` consumers cannot
        mutate the slot through their returned copy.
        """
        # Defensive runtime check — Python type hints are advisory; a
        # caller may still pass None or a non-dict despite the
        # ``dict[str, Any]`` annotation.
        if not isinstance(details, dict):
            raise ValueError(
                "ProgressReporter.report: details must be a non-None dict, "
                f"got {type(details).__name__}"
            )

        sensitive = find_sensitive_keys(details.keys())
        if sensitive:
            # Match Java SensitiveDataValidator: refuse, don't log the
            # values (which would defeat the point).
            raise ValueError(
                "ProgressReporter.report: details contains sensitive key(s) "
                f"{sensitive!r}; credentials must not be persisted in progress payloads"
            )

        snapshot = dict(details)  # shallow copy, defensive against caller mutation
        with self._lock:
            self._snapshot = snapshot

    def latest(self) -> dict[str, Any] | None:
        """Return the most recent snapshot, or ``None`` if never reported.

        The returned dict is a **shallow copy** — mutating it does not
        affect the stored snapshot. Returns ``None`` (not an empty
        dict) when :meth:`report` has never been called, mirroring the
        Java SDK's nullable contract.
        """
        with self._lock:
            current = self._snapshot
        if current is None:
            return None
        return dict(current)
