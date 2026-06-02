"""batch-worker-sdk: Python SDK for the file-batch-system worker protocol.

Package layout strictly mirrors the Java SDK (``com.example.batch.sdk.*``):

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

Public surface (only canonical paths — no compat shims):

- :class:`BatchPlatformClient`, :class:`BatchPlatformClientConfig`
- :class:`SdkTaskHandler`, ``@batch_task``, :func:`collect_registered_handlers`
- :class:`SdkTaskContext`, :class:`SdkTaskResult`, :class:`SdkTaskTypeDescriptor`
- :class:`WorkerRuntimeState`, :class:`ProgressReporter`, :class:`CancellationSignal`
- Exception hierarchy (:class:`PlatformError` + four typed subclasses)

Intentionally **not** exported: the :mod:`internal`, :mod:`retry`, and
:mod:`scheduler` modules — package-internal plumbing.
"""

from __future__ import annotations

from batch_worker_sdk._version import __version__
from batch_worker_sdk.client.client import BatchPlatformClient
from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher
from batch_worker_sdk.exceptions import (
    AuthError,
    ConflictError,
    PersistentClientError,
    PlatformError,
    TransientError,
)
from batch_worker_sdk.handler._decorator import batch_task, collect_registered_handlers
from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.task.cancellation import CancellationSignal
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.progress import ProgressReporter
from batch_worker_sdk.task.result import SdkTaskResult
from batch_worker_sdk.task.state import WorkerRuntimeState

__all__: list[str] = [
    "AuthError",
    "BatchPlatformClient",
    "BatchPlatformClientConfig",
    "CancellationSignal",
    "ConflictError",
    "PersistentClientError",
    "PlatformError",
    "ProgressReporter",
    "SdkTaskContext",
    "SdkTaskHandler",
    "SdkTaskResult",
    "SdkTaskTypeDescriptor",
    "TaskDispatcher",
    "TransientError",
    "WorkerRuntimeState",
    "__version__",
    "batch_task",
    "collect_registered_handlers",
]
