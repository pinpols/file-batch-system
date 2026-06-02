"""Reusable test fixtures and helpers.

- :func:`make_test_context` — quick ``SdkTaskContext`` builder with
  sane defaults (mirrors Java ``TaskDispatchMessageBuilder``).
- :func:`make_test_config` — quick ``BatchPlatformClientConfig`` builder.
- :class:`RecordingHandler` — :class:`SdkTaskHandler` impl that records
  every invocation; lets tenant tests assert "execute called with X".
- :func:`fake_platform` — pytest fixture that starts/stops a
  :class:`FakeBatchPlatform` automatically.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import timedelta
from typing import Any

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult
from batch_worker_sdk.testkit.fake_platform import FakeBatchPlatform


def make_test_context(
    task_id: int = 1,
    tenant_id: str = "test-tenant",
    worker_code: str = "test-worker",
    task_type: str = "test-task",
    parameters: dict[str, Any] | None = None,
    **overrides: Any,
) -> SdkTaskContext:
    """Build a valid :class:`SdkTaskContext` for unit tests.

    Defaults match the smallest realistic dispatch — handlers that
    only read a subset of fields can ignore the rest. ``**overrides``
    lets callers pin any other field (``biz_date``, ``attempt_no``,
    ``runtime_attributes``, etc.).
    """
    kwargs: dict[str, Any] = {
        "task_id": task_id,
        "tenant_id": tenant_id,
        "worker_code": worker_code,
        "task_type": task_type,
        "parameters": parameters or {},
    }
    kwargs.update(overrides)
    return SdkTaskContext(**kwargs)


def make_test_config(
    base_url: str = "http://localhost:8081",
    tenant_id: str = "test-tenant",
    worker_code: str = "test-worker",
    **overrides: Any,
) -> BatchPlatformClientConfig:
    """Build a valid :class:`BatchPlatformClientConfig` for unit tests.

    Timing knobs default to the minimum legal values
    (heartbeat=5s, lease=5s, http=1s) so config validation passes
    without callers needing to learn the rules.
    """
    kwargs: dict[str, Any] = {
        "base_url": base_url,
        "tenant_id": tenant_id,
        "worker_code": worker_code,
        "http_timeout": timedelta(seconds=1),
        "heartbeat_interval": timedelta(seconds=5),
        "lease_renew_interval": timedelta(seconds=5),
        "retry_base_delay": timedelta(milliseconds=1),
    }
    kwargs.update(overrides)
    return BatchPlatformClientConfig(**kwargs)


class RecordingHandler:
    """Records every ``execute`` invocation — for tenant test asserts.

    Implements the structural :class:`SdkTaskHandler` protocol; tests
    pass an instance in via decorator-collect or direct
    ``client.register_handler`` (Lane T).

    Example::

        rec = RecordingHandler(task_type="my-job")
        await rec.execute(make_test_context(task_id=42))
        assert rec.calls[0].task_id == 42
    """

    def __init__(
        self,
        task_type_value: str = "test-task",
        descriptor_value: SdkTaskTypeDescriptor | None = None,
        result: SdkTaskResult | None = None,
    ) -> None:
        self._task_type = task_type_value
        self._descriptor = descriptor_value
        self._result = result or SdkTaskResult.success_with({}, "recorded")
        self.calls: list[SdkTaskContext] = []
        self.cancel_calls: list[SdkTaskContext] = []

    def task_type(self) -> str:
        return self._task_type

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        self.calls.append(ctx)
        return self._result

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        return self._descriptor

    def cancel(self, ctx: SdkTaskContext) -> None:
        self.cancel_calls.append(ctx)


async def fake_platform() -> AsyncIterator[FakeBatchPlatform]:
    """Async-generator helper — yields a started :class:`FakeBatchPlatform`.

    Wrap in ``pytest.fixture`` (or ``pytest_asyncio.fixture``) at the
    test module level — keeping the wrapping out of the SDK package
    means importing :mod:`batch_worker_sdk.testkit` doesn't pull in
    pytest as a runtime dep. Tenants who don't use pytest can call
    :class:`FakeBatchPlatform` directly via ``async with``.
    """
    platform = FakeBatchPlatform()
    await platform.start()
    try:
        yield platform
    finally:
        await platform.stop()
