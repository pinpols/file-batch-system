"""跨包公共执行契约。"""

from __future__ import annotations

import pytest

from tests.handler.conftest import get_attr, require_module

BUILTIN_INHERITANCE = [
    (
        ("batch_worker_sdk.handler.builtin", "FileImportHandler"),
        ("batch_worker_sdk.handler", "SdkAbstractImportHandler"),
    ),
    (
        ("batch_worker_sdk.handler.builtin", "HttpDispatchHandler"),
        ("batch_worker_sdk.handler", "SdkAbstractDispatchHandler"),
    ),
    (
        ("batch_worker_sdk.handler.builtin", "QueryExportHandler"),
        ("batch_worker_sdk.handler", "SdkAbstractExportHandler"),
    ),
]

TYPED_PAIRS = [
    (
        ("batch_worker_sdk.handler.typed", "SdkAbstractTypedImportHandler"),
        ("batch_worker_sdk.handler", "SdkAbstractImportHandler"),
    ),
    (
        ("batch_worker_sdk.handler.typed", "SdkAbstractTypedExportHandler"),
        ("batch_worker_sdk.handler", "SdkAbstractExportHandler"),
    ),
    (
        ("batch_worker_sdk.handler.typed", "SdkAbstractTypedProcessHandler"),
        ("batch_worker_sdk.handler", "SdkAbstractProcessHandler"),
    ),
    (
        ("batch_worker_sdk.handler.typed", "SdkAbstractTypedDispatchHandler"),
        ("batch_worker_sdk.handler", "SdkAbstractDispatchHandler"),
    ),
]


@pytest.mark.parametrize(("child", "parent"), BUILTIN_INHERITANCE)
def test_builtin_handler_exposes_task_handler_surface(
    child: tuple[str, str], parent: tuple[str, str]
) -> None:
    child_mod = require_module(child[0])
    child_cls = get_attr(child_mod, child[1])
    for method in ("task_type", "descriptor", "cancel", "execute"):
        assert callable(getattr(child_cls, method, None)), f"{child[1]} missing {method}"


@pytest.mark.parametrize(("typed", "untyped"), TYPED_PAIRS)
def test_typed_base_reuses_shared_template(
    typed: tuple[str, str], untyped: tuple[str, str]
) -> None:
    """Python typed 形态统一继承异步模板基类。"""
    typed_mod = require_module(typed[0])
    typed_cls = get_attr(typed_mod, typed[1])

    handler_mod = require_module("batch_worker_sdk.handler")
    template_base = get_attr(handler_mod, "SdkAbstractTaskHandler")
    assert issubclass(typed_cls, template_base)


def test_sdk_row_result_is_exposed_from_handler_package() -> None:
    """Java SdkRowResult 是 4 个 long-task 模板共用的行计数器。
    Python 必须暴露等价类型,带 success/skipped/failed/reject。"""
    mod = require_module("batch_worker_sdk.handler")
    cls = get_attr(mod, "SdkRowResult")
    instance = cls()
    # API 表面检查:4 个 counter + total。
    for attr in ("success", "skipped", "failed", "reject", "total"):
        assert hasattr(instance, attr), f"SdkRowResult missing {attr!r}"


def test_sdk_typed_parameters_is_exposed() -> None:
    """typed handler 输入解析所用的 SdkTypedParameters 基类。"""
    mod = require_module("batch_worker_sdk.handler.typed")
    get_attr(mod, "SdkTypedParameters")
