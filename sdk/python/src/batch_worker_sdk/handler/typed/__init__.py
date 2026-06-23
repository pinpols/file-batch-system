"""Typed handler 模板 —— 对齐 Java ``io.github.pinpols.batch.sdk.handler.typed``。

提供 5 种 SDK handler 形态的强类型(pydantic 支撑)变体:单方法
:class:`SdkTypedTaskHandler`(任意任务),以及 4 个行流水模板
(Import/Export/Process/Dispatch),它们的 ``Params`` 泛型在 pydantic
``BaseModel`` 子类上闭合。框架在任务起始时一次性反序列化
``ctx.parameters``;租户代码拿到的是经过校验的模型实例 —— 无需手工
``ctx.parameters["foo"]`` 强转。

公开命名刻意与 Java 对齐,以便 Java ↔ Python 对等测试保持 1:1。
"""

from __future__ import annotations

from batch_worker_sdk.handler.typed._typed_dispatch import (
    SdkAbstractTypedDispatchHandler,
)
from batch_worker_sdk.handler.typed._typed_export import SdkAbstractTypedExportHandler
from batch_worker_sdk.handler.typed._typed_import import SdkAbstractTypedImportHandler
from batch_worker_sdk.handler.typed._typed_parameters import SdkTypedParameters
from batch_worker_sdk.handler.typed._typed_process import (
    SdkAbstractTypedProcessHandler,
)
from batch_worker_sdk.handler.typed._typed_task_handler import SdkTypedTaskHandler

__all__: list[str] = [
    "SdkAbstractTypedDispatchHandler",
    "SdkAbstractTypedExportHandler",
    "SdkAbstractTypedImportHandler",
    "SdkAbstractTypedProcessHandler",
    "SdkTypedParameters",
    "SdkTypedTaskHandler",
]
