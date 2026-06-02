"""内置 handler 模板 —— 对齐 Java ``handler/builtin/``。

ADR-036 三种形态,每种拆成配置 record + handler 模板,租户继承后可插入
自己的目的端 / 源 / 目标列表:

==================================  ==========================================
Java                                Python
==================================  ==========================================
``FileImportHandler`` / Config       :class:`FileImportHandler` / :class:`FileImportConfig`
``HttpDispatchHandler`` / Config     :class:`HttpDispatchHandler` / :class:`HttpDispatchConfig`
``QueryExportHandler`` / Config      :class:`QueryExportHandler` / :class:`QueryExportConfig`
==================================  ==========================================

对齐 Java handler/builtin/(FileImport / HttpDispatch / QueryExport),钩子方法
名(``_open_source`` / ``_read_rows`` / ``_load_batch`` / ``_resolve_targets``
/ ``_dispatch_to_target`` / ``_query_rows`` / ``_write_row``)与 Java ABC 契约
完全一致。
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
