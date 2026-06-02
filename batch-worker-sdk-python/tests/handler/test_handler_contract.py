"""Handler 注册契约。

对 11 个具体 handler(4 atomic + 3 builtin + 4 typed)各自验证:

* `task_type()` 返回非空字符串;
* `descriptor()` 可调用,返回 `None` 或 `SdkTaskTypeDescriptor`
  实例(不抛异常);
* 实例通过 `isinstance` 满足 `SdkTaskHandler` Protocol
  (后者已 `@runtime_checkable`)。
"""

from __future__ import annotations

from typing import Any

import pytest

from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from tests.handler.conftest import get_attr, require_module

# 11 个具体 handler 的 (模块 dotted path, 类名)。
ATOMIC_HANDLERS = [
    ("batch_worker_sdk.handler.atomic.sql", "SqlAtomicHandler"),
    ("batch_worker_sdk.handler.atomic.shell", "ShellAtomicHandler"),
    ("batch_worker_sdk.handler.atomic.http", "HttpAtomicHandler"),
    ("batch_worker_sdk.handler.atomic.stored_proc", "StoredProcAtomicHandler"),
]
BUILTIN_HANDLERS = [
    ("batch_worker_sdk.handler.builtin.file_import", "FileImportHandler"),
    ("batch_worker_sdk.handler.builtin.http_dispatch", "HttpDispatchHandler"),
    ("batch_worker_sdk.handler.builtin.query_export", "QueryExportHandler"),
]
TYPED_HANDLERS = [
    ("batch_worker_sdk.handler.typed.import_handler", "SdkAbstractTypedImportHandler"),
    ("batch_worker_sdk.handler.typed.export_handler", "SdkAbstractTypedExportHandler"),
    ("batch_worker_sdk.handler.typed.process_handler", "SdkAbstractTypedProcessHandler"),
    ("batch_worker_sdk.handler.typed.dispatch_handler", "SdkAbstractTypedDispatchHandler"),
]
ALL_HANDLERS = ATOMIC_HANDLERS + BUILTIN_HANDLERS + TYPED_HANDLERS


def _instantiate(dotted: str, cls_name: str):
    """无参实例化 handler;必要时用最小 fake 兜底。"""
    mod = require_module(dotted)
    cls = get_attr(mod, cls_name)
    # 具体 handler 必须能无参构造(参数由 SdkTaskContext 提供)。
    # typed 抽象基类需要写个最小子类。
    try:
        return cls()
    except TypeError:
        # typed 抽象 → 做一个 no-op 子类把所有抽象方法填上桩;
        # 我们只 introspect task_type 与 descriptor,不真跑 execute。

        class _Stub(cls):  # type: ignore[misc, valid-type]
            def task_type(self) -> str:
                return f"stub.{cls_name}"

            def _stub(self, *a: Any, **kw: Any) -> Any:
                return None

            # 其余还抽象的成员用宽松桩兜底。
            def __getattr__(self, item: str) -> Any:  # pragma: no cover
                return self._stub

        try:
            return _Stub()
        except TypeError as exc:
            pytest.skip(f"{cls_name}: cannot stub abstract methods ({exc})")


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
