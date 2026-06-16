"""Atomic handler 模板集合(ADR-036 atomic 形态)。

与 Java ``handler/atomic/`` 1:1 对齐 ——
``HttpAtomicHandler`` / ``ShellAtomicHandler`` / ``SqlAtomicHandler`` /
``StoredProcAtomicHandler`` 及与之配套的 ``*Config`` 值对象。

租户按需 import 需要的 handler + config;``[sql]`` extra 会装上
:mod:`asyncpg`,供 SQL / StoredProc handler 使用(HTTP / Shell 无额外依赖)。
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
