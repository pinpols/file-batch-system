"""handler 契约测试的共享 fixture。"""

from __future__ import annotations

from types import ModuleType
from typing import Any

import pytest

from batch_worker_sdk.task.context import SdkTaskContext


def require_module(dotted: str) -> ModuleType:
    """导入被测公开模块,缺失时让契约测试直接失败。"""
    return __import__(dotted, fromlist=["*"])


def get_attr(module: ModuleType, name: str) -> Any:
    """读取公开属性,缺失时让契约测试直接失败。"""
    return getattr(module, name)


@pytest.fixture
def base_ctx() -> SdkTaskContext:
    """适合做 hook 顺序断言的最小 SdkTaskContext。"""
    return SdkTaskContext(
        tenant_id="t-1",
        task_id=42,
        worker_code="worker-py-1",
        task_type="contract-test",
        parameters={},
        runtime_attributes={},
    )


def make_ctx(**overrides: Any) -> SdkTaskContext:
    """构造可覆盖字段的 SdkTaskContext。"""
    base: dict[str, Any] = {
        "tenant_id": "t-1",
        "task_id": 42,
        "worker_code": "worker-py-1",
        "task_type": "contract-test",
        "parameters": {},
        "runtime_attributes": {},
    }
    base.update(overrides)
    return SdkTaskContext(**base)
