"""可复用测试 fixtures 与辅助函数。

- :func:`make_test_context` —— 带合理默认值的 ``SdkTaskContext``
  快速构造器(对标 Java ``TaskDispatchMessageBuilder``)。
- :func:`make_test_config` —— ``BatchPlatformClientConfig`` 快速构造器。
- :class:`RecordingHandler` —— 记录每次调用的 :class:`SdkTaskHandler`
  实现,让租户测试可以断言 "execute 被以 X 调用"。
- :func:`fake_platform` —— 自动启停 :class:`FakeBatchPlatform` 的
  pytest fixture。
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
    """构造一个合法的 :class:`SdkTaskContext` 用于单测。

    默认值匹配最小可用的派发 —— 只读取字段子集的 handler 可以
    忽略其余字段。``**overrides`` 允许调用方指定任意其它字段
    (``biz_date`` / ``attempt_no`` / ``runtime_attributes`` 等)。
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
    """构造一个合法的 :class:`BatchPlatformClientConfig` 用于单测。

    时间相关参数默认取最小合法值(heartbeat=5s / lease=5s / http=1s),
    让 config 校验直接通过,调用方不用先去学规则。
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
    """记录每次 ``execute`` 调用 —— 供租户测试断言。

    实现结构化的 :class:`SdkTaskHandler` 协议;测试可通过
    decorator-collect 或直接 ``client.register_handler``(Lane T)
    传入实例。

    例如::

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
    """异步生成器辅助 —— yield 一个已启动的 :class:`FakeBatchPlatform`。

    在测试模块层包成 ``pytest.fixture``(或 ``pytest_asyncio.fixture``)。
    把这一层包装放在 SDK 包外,意味着 import
    :mod:`batch_worker_sdk.testkit` 不会把 pytest 拖成运行时依赖。
    不用 pytest 的租户可以直接 ``async with`` :class:`FakeBatchPlatform`。
    """
    platform = FakeBatchPlatform()
    await platform.start()
    try:
        yield platform
    finally:
        await platform.stop()
