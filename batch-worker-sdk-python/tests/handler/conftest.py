"""handler 契约测试的共享 fixture + 软 import helper。

4 个兄弟 feature 分支(abstract-base / atomic / builtin / typed)各自
独立落地。在 4 个都进 main 之前,某些具体 handler 模块可能不存在。
我们暴露 `try_import` 让每个测试模块在依赖缺失时干净地短路
(pytest.skip),而不是让整套测试 fail。
"""

from __future__ import annotations

import importlib
from types import ModuleType
from typing import Any

import pytest

from batch_worker_sdk.task.context import SdkTaskContext


def try_import(dotted: str) -> ModuleType | None:
    """尽量 import:失败时返回 None 而不是抛 ImportError。

    handler 契约测试用它,当兄弟分支的模块缺失时跳过该测试,
    而不是把整套测试拖垮。
    """
    try:
        return importlib.import_module(dotted)
    except ImportError:
        return None


def require_module(dotted: str) -> ModuleType:
    """import 不到就 pytest.skip —— 在测试体内调用。"""
    mod = try_import(dotted)
    if mod is None:
        pytest.skip(f"dependency module {dotted!r} not yet merged")
    return mod


def get_attr(module: ModuleType, name: str) -> Any:
    """从兄弟分支模块读取属性,读不到就 skip。"""
    if not hasattr(module, name):
        pytest.skip(f"{module.__name__!r} has no attribute {name!r} (sibling branch incomplete)")
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
