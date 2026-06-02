"""Builtin handler templates — mirror of Java ``handler/builtin/``.

Three ADR-036 shapes, each split into a config record + handler template
the tenant can subclass to plug its destination / source / target list:

==================================  ==========================================
Java                                Python
==================================  ==========================================
``FileImportHandler`` / Config       :class:`FileImportHandler` / :class:`FileImportConfig`
``HttpDispatchHandler`` / Config     :class:`HttpDispatchHandler` / :class:`HttpDispatchConfig`
``QueryExportHandler`` / Config      :class:`QueryExportHandler` / :class:`QueryExportConfig`
==================================  ==========================================

The Java SDK's ``SdkAbstractImportHandler`` / ``SdkAbstractDispatchHandler``
/ ``SdkAbstractExportHandler`` ABC bases have a Python counterpart lane
still in flight; until that lands, these builtins satisfy the structural
:class:`~batch_worker_sdk.handler.SdkTaskHandler` protocol directly. Hook
method names (``_open_source`` / ``_read_rows`` / ``_load_batch`` /
``_resolve_targets`` / ``_dispatch_to_target`` / ``_query_rows`` /
``_write_row``) already match the ABC contract, so the rebase will be
inheritance-only — no rename.
"""

from __future__ import annotations

from batch_worker_sdk.handler.builtin._file_import import (
    FileFormat,
    FileImportConfig,
    FileImportHandler,
)
from batch_worker_sdk.handler.builtin._http_dispatch import (
    HttpDispatchConfig,
    HttpDispatchHandler,
    HttpDispatchTarget,
)
from batch_worker_sdk.handler.builtin._query_export import (
    ExportFormat,
    QueryExportConfig,
    QueryExportHandler,
)

__all__: list[str] = [
    "ExportFormat",
    "FileFormat",
    "FileImportConfig",
    "FileImportHandler",
    "HttpDispatchConfig",
    "HttpDispatchHandler",
    "HttpDispatchTarget",
    "QueryExportConfig",
    "QueryExportHandler",
]
