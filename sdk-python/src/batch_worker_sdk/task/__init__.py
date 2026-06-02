"""Task subpackage — mirror of Java ``com.example.batch.sdk.task``.

Data + execution-context models: :class:`SdkTaskContext`,
:class:`SdkTaskResult`, :class:`SdkTaskTypeDescriptor`,
:class:`WorkerRuntimeState`, :class:`ProgressReporter`,
:class:`CancellationSignal`.
"""

from __future__ import annotations

from batch_worker_sdk.task.cancellation import CancellationSignal
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.progress import ProgressReporter
from batch_worker_sdk.task.result import SdkTaskResult
from batch_worker_sdk.task.state import WorkerRuntimeState

__all__: list[str] = [
    "CancellationSignal",
    "ProgressReporter",
    "SdkTaskContext",
    "SdkTaskResult",
    "SdkTaskTypeDescriptor",
    "WorkerRuntimeState",
]
