"""batch-worker-sdk:file-batch-system worker 协议的 Python SDK。

包结构严格对齐 Java SDK(``com.example.batch.sdk.*``):

==========================================================  ===================================
Java                                                        Python
==========================================================  ===================================
``com.example.batch.sdk.client``                            :mod:`batch_worker_sdk.client`
``com.example.batch.sdk.dispatcher``                        :mod:`batch_worker_sdk.dispatcher`
``com.example.batch.sdk.handler``                           :mod:`batch_worker_sdk.handler`
``com.example.batch.sdk.internal``                          :mod:`batch_worker_sdk.internal`
``com.example.batch.sdk.retry``                             :mod:`batch_worker_sdk.retry`
``com.example.batch.sdk.scheduler``                         :mod:`batch_worker_sdk.scheduler`
``com.example.batch.sdk.task``                              :mod:`batch_worker_sdk.task`
==========================================================  ===================================

对外公开的入口(只保留 canonical 路径,不提供兼容 shim):

- :class:`BatchPlatformClient`、:class:`BatchPlatformClientConfig`
- :class:`SdkTaskHandler`、``@batch_task``、:func:`collect_registered_handlers`
- :class:`SdkTaskContext`、:class:`SdkTaskResult`、:class:`SdkTaskTypeDescriptor`
- :class:`WorkerRuntimeState`、:class:`ProgressReporter`、:class:`CancellationSignal`
- 异常体系(:class:`PlatformError` + 四个类型化子类)

刻意 **不** 对外导出 :mod:`internal`、:mod:`retry`、:mod:`scheduler` —— 这些
都是包内私有实现。
"""

from __future__ import annotations

from batch_worker_sdk._version import __version__
from batch_worker_sdk.client.client import BatchPlatformClient
from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.constants import (
    SCHEMA_VERSIONS_SUPPORTED,
    SENSITIVE_KEYWORDS,
    TASK_STATUSES,
    WORKER_RUNTIME_STATES,
)
from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher
from batch_worker_sdk.exceptions import (
    AuthError,
    ConflictError,
    PersistentClientError,
    PlatformError,
    SdkTaskStopped,
    TransientError,
)
from batch_worker_sdk.handler._atomic import SdkAbstractAtomicHandler
from batch_worker_sdk.handler._base import SdkAbstractTaskHandler, SdkRowResult
from batch_worker_sdk.handler._decorator import batch_task, collect_registered_handlers
from batch_worker_sdk.handler._dispatch import SdkAbstractDispatchHandler
from batch_worker_sdk.handler._export import SdkAbstractExportHandler
from batch_worker_sdk.handler._import import SdkAbstractImportHandler
from batch_worker_sdk.handler._process import SdkAbstractProcessHandler
from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.idempotent import (
    InMemoryIdempotencyStore,
    NoOpIdempotencyStore,
    SdkIdempotencyEntity,
    SdkIdempotencyStore,
    SdkIdempotentHandler,
    idempotent,
    wrap_idempotent,
)
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
    "SCHEMA_VERSIONS_SUPPORTED",
    "SENSITIVE_KEYWORDS",
    "TASK_STATUSES",
    "WORKER_RUNTIME_STATES",
    "AuthError",
    "BatchPlatformClient",
    "BatchPlatformClientConfig",
    "CancellationSignal",
    "ConflictError",
    "InMemoryCheckpoint",
    "InMemoryIdempotencyStore",
    "NoOpIdempotencyStore",
    "PersistentClientError",
    "PlatformError",
    "ProgressReporter",
    "SdkAbstractAtomicHandler",
    "SdkAbstractDispatchHandler",
    "SdkAbstractExportHandler",
    "SdkAbstractImportHandler",
    "SdkAbstractProcessHandler",
    "SdkAbstractTaskHandler",
    "SdkCheckpoint",
    "SdkCheckpointState",
    "SdkIdempotencyEntity",
    "SdkIdempotencyStore",
    "SdkIdempotentHandler",
    "SdkRowResult",
    "SdkTaskContext",
    "SdkTaskHandler",
    "SdkTaskResult",
    "SdkTaskStopped",
    "SdkTaskTypeDescriptor",
    "TaskDispatcher",
    "TransientError",
    "WorkerRuntimeState",
    "__version__",
    "batch_task",
    "collect_registered_handlers",
    "idempotent",
    "wrap_idempotent",
]
