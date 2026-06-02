"""Handler 子包 —— 对齐 Java ``com.example.batch.sdk.handler``。

承载 :class:`SdkTaskHandler` Protocol、声明式 ``@batch_task`` 装饰器,以及对齐
Java ``handler/`` 根目录的 5 个形态抽象类(``SdkAbstractTaskHandler`` + 5 个
形态子类 + ``SdkRowResult``)。子包 :mod:`atomic`、:mod:`builtin`、:mod:`typed`
对齐 Java handler/{atomic,builtin,typed} 子包。
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
