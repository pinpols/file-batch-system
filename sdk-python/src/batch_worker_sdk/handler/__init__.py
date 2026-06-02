"""Handler subpackage — mirror of Java ``com.example.batch.sdk.handler``.

Houses the :class:`SdkTaskHandler` Protocol, the declarative
``@batch_task`` decorator, and the 5 shape ABCs that mirror the Java
``handler/`` root (``SdkAbstractTaskHandler`` + 5 shape subclasses +
``SdkRowResult``). Subpackages :mod:`atomic`, :mod:`builtin`, and
:mod:`typed` are placeholders for the Java ``handler/atomic/``,
``handler/builtin/``, and ``handler/typed/`` lanes.
"""

from __future__ import annotations

from batch_worker_sdk.handler._atomic import SdkAbstractAtomicHandler
from batch_worker_sdk.handler._base import SdkAbstractTaskHandler, SdkRowResult
from batch_worker_sdk.handler._decorator import batch_task, collect_registered_handlers
from batch_worker_sdk.handler._dispatch import SdkAbstractDispatchHandler
from batch_worker_sdk.handler._export import SdkAbstractExportHandler
from batch_worker_sdk.handler._import import SdkAbstractImportHandler
from batch_worker_sdk.handler._process import SdkAbstractProcessHandler
from batch_worker_sdk.handler.handler import SdkTaskHandler

__all__: list[str] = [
    "SdkAbstractAtomicHandler",
    "SdkAbstractDispatchHandler",
    "SdkAbstractExportHandler",
    "SdkAbstractImportHandler",
    "SdkAbstractProcessHandler",
    "SdkAbstractTaskHandler",
    "SdkRowResult",
    "SdkTaskHandler",
    "batch_task",
    "collect_registered_handlers",
]
