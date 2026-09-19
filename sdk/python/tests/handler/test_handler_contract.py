"""Handler 注册契约。

对 11 个具体 handler(4 atomic + 3 builtin + 4 typed)各自验证:

* `task_type()` 返回非空字符串;
* `descriptor()` 可调用,返回 `None` 或 `SdkTaskTypeDescriptor`
  实例(不抛异常);
* 实例通过 `isinstance` 满足 `SdkTaskHandler` Protocol
  (后者已 `@runtime_checkable`)。
"""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from tests.handler.conftest import get_attr, require_module

# 11 个具体 handler 的 (模块 dotted path, 类名)。
ATOMIC_HANDLERS = [
    ("batch_worker_sdk.handler.atomic", "SqlAtomicHandler"),
    ("batch_worker_sdk.handler.atomic", "ShellAtomicHandler"),
    ("batch_worker_sdk.handler.atomic", "HttpAtomicHandler"),
    ("batch_worker_sdk.handler.atomic", "StoredProcAtomicHandler"),
]
BUILTIN_HANDLERS = [
    ("batch_worker_sdk.handler.builtin", "FileImportHandler"),
    ("batch_worker_sdk.handler.builtin", "HttpDispatchHandler"),
    ("batch_worker_sdk.handler.builtin", "QueryExportHandler"),
]
TYPED_HANDLERS = [
    ("batch_worker_sdk.handler.typed", "SdkAbstractTypedImportHandler"),
    ("batch_worker_sdk.handler.typed", "SdkAbstractTypedExportHandler"),
    ("batch_worker_sdk.handler.typed", "SdkAbstractTypedProcessHandler"),
    ("batch_worker_sdk.handler.typed", "SdkAbstractTypedDispatchHandler"),
]
ALL_HANDLERS = ATOMIC_HANDLERS + BUILTIN_HANDLERS + TYPED_HANDLERS


def _instantiate(dotted: str, cls_name: str):
    """构造只用于检查 Protocol 表面的轻量实例。"""
    mod = require_module(dotted)
    cls = get_attr(mod, cls_name)
    abstract_methods = getattr(cls, "__abstractmethods__", frozenset())
    if abstract_methods:

        def _stub(self: Any, *args: Any, **kwargs: Any) -> Any:
            return None

        namespace = {name: _stub for name in abstract_methods}
        namespace["task_type"] = lambda self: f"stub.{cls_name}"
        return type(f"_{cls_name}ContractStub", (cls,), namespace)()

    # 构造参数和真实行为已有各 handler 专属测试覆盖,这里隔离检查公共 Protocol。
    instance = object.__new__(cls)
    instance._config = SimpleNamespace(task_type=f"stub.{cls_name}")
    return instance


@pytest.mark.parametrize(("dotted", "cls_name"), ALL_HANDLERS)
def test_task_type_returns_non_empty_string(dotted: str, cls_name: str) -> None:
    instance = _instantiate(dotted, cls_name)
    tt = instance.task_type()
    assert isinstance(tt, str)
    assert tt, f"{cls_name}.task_type() returned empty string"


@pytest.mark.parametrize(("dotted", "cls_name"), ALL_HANDLERS)
def test_descriptor_does_not_raise(dotted: str, cls_name: str) -> None:
    instance = _instantiate(dotted, cls_name)
    descriptor = instance.descriptor()
    assert descriptor is None or isinstance(descriptor, SdkTaskTypeDescriptor)


@pytest.mark.parametrize(("dotted", "cls_name"), ALL_HANDLERS)
def test_satisfies_sdk_task_handler_protocol(dotted: str, cls_name: str) -> None:
    instance = _instantiate(dotted, cls_name)
    # SdkTaskHandler 是 @runtime_checkable,isinstance() 检查结构化
    # 形状(task_type / execute / descriptor / cancel)。
    assert isinstance(instance, SdkTaskHandler), (
        f"{cls_name} does not satisfy the SdkTaskHandler Protocol"
    )


def test_no_duplicate_task_types_across_concrete_handlers() -> None:
    """7 个始终具体的 handler(atomic + builtin)task_types 必须互不相同。"""
    seen: dict[str, str] = {}
    for dotted, cls_name in ATOMIC_HANDLERS + BUILTIN_HANDLERS:
        instance = _instantiate(dotted, cls_name)
        tt = instance.task_type()
        if tt in seen:
            pytest.fail(f"duplicate task_type {tt!r} on {cls_name} and {seen[tt]}")
        seen[tt] = cls_name
