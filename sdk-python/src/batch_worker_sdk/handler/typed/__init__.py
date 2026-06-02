"""Typed handler templates — mirror of Java
``com.example.batch.sdk.handler.typed``.

Provides strongly-typed (pydantic-backed) variants of the 5 SDK
handler shapes: a single-method :class:`SdkTypedTaskHandler` for
arbitrary tasks, and 4 row-flow templates (Import/Export/Process/
Dispatch) whose ``Params`` generic is closed on a pydantic
``BaseModel`` subclass. The framework deserializes
``ctx.parameters`` once at task start; tenant code receives a
validated model instance — no manual ``ctx.parameters["foo"]``
casting.

Public surface intentionally matches Java naming so the Java↔Python
parity tests stay 1:1.
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
