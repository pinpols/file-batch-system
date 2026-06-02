"""Atomic handler templates (ADR-036 atomic shapes).

Aligns 1:1 with Java ``handler/atomic/`` —
``HttpAtomicHandler`` / ``ShellAtomicHandler`` / ``SqlAtomicHandler`` /
``StoredProcAtomicHandler`` plus their matching ``*Config`` value
objects.

Tenants opt in by importing the handler + config they need; the
``[sql]`` extra installs :mod:`asyncpg` for the SQL / StoredProc
handlers (HTTP / Shell have no extra deps).
"""

from __future__ import annotations

from batch_worker_sdk.handler.atomic._http import HttpAtomicConfig, HttpAtomicHandler
from batch_worker_sdk.handler.atomic._shell import ShellAtomicConfig, ShellAtomicHandler
from batch_worker_sdk.handler.atomic._sql import (
    SqlAtomicConfig,
    SqlAtomicHandler,
    SqlConnection,
    SqlConnectionFactory,
)
from batch_worker_sdk.handler.atomic._stored_proc import (
    StoredProcAtomicConfig,
    StoredProcAtomicHandler,
)

__all__: list[str] = [
    "HttpAtomicConfig",
    "HttpAtomicHandler",
    "ShellAtomicConfig",
    "ShellAtomicHandler",
    "SqlAtomicConfig",
    "SqlAtomicHandler",
    "SqlConnection",
    "SqlConnectionFactory",
    "StoredProcAtomicConfig",
    "StoredProcAtomicHandler",
]
