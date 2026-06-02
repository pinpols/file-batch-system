"""typed handler 测试的共享 fixture。"""

from __future__ import annotations

from typing import Any

from batch_worker_sdk.task.context import SdkTaskContext


def make_ctx(parameters: dict[str, Any] | None = None) -> SdkTaskContext:
    return SdkTaskContext(
        tenant_id="t1",
        task_id=42,
        worker_code="w-1",
        task_type="typed-test",
        parameters=parameters or {},
    )
