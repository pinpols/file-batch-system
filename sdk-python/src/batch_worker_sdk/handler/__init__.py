"""Handler subpackage — mirror of Java ``com.example.batch.sdk.handler``.

Houses the :class:`SdkTaskHandler` Protocol and the declarative
``@batch_task`` decorator. Subpackages :mod:`atomic`, :mod:`builtin`,
and :mod:`typed` are placeholders for future SDK lanes that will mirror
Java ``handler/atomic/``, ``handler/builtin/``, and ``handler/typed/``.
"""

from __future__ import annotations

from batch_worker_sdk.handler._decorator import batch_task, collect_registered_handlers
from batch_worker_sdk.handler.handler import SdkTaskHandler

__all__: list[str] = [
    "SdkTaskHandler",
    "batch_task",
    "collect_registered_handlers",
]
