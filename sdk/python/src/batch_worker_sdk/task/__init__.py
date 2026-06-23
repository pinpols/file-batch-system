"""Task 子包 —— 对齐 Java ``io.github.pinpols.batch.sdk.task``。

数据 + 执行上下文模型::class:`SdkTaskContext`、
:class:`SdkTaskResult`、:class:`SdkTaskTypeDescriptor`、
:class:`WorkerRuntimeState`、:class:`ProgressReporter`、
:class:`CancellationSignal`。
"""

from __future__ import annotations

from batch_worker_sdk.task.cancellation import CancellationSignal
from batch_worker_sdk.task.checkpoint import (
    InMemoryCheckpoint,
    SdkCheckpoint,
    SdkCheckpointState,
)
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.progress import ProgressReporter
from batch_worker_sdk.task.result import SdkTaskResult
from batch_worker_sdk.task.state import WorkerRuntimeState

__all__: list[str] = [
    "CancellationSignal",
    "InMemoryCheckpoint",
    "ProgressReporter",
    "SdkCheckpoint",
    "SdkCheckpointState",
    "SdkTaskContext",
    "SdkTaskResult",
    "SdkTaskTypeDescriptor",
    "WorkerRuntimeState",
]
