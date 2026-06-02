"""Scheduler subpackage — mirror of Java ``com.example.batch.sdk.scheduler``.

Houses :class:`HeartbeatScheduler`, :class:`LeaseRenewalScheduler`,
and the platform-directive parser. Internals only — public callers go
through :class:`batch_worker_sdk.client.BatchPlatformClient`.
"""

from __future__ import annotations

__all__: list[str] = []
