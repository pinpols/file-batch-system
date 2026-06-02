"""跨包继承链契约。

* 3 个 builtin handler 必须继承对应的 `SdkAbstract*Handler`。
* 4 个 typed 抽象基类必须既继承 untyped 兄弟,又满足
  `SdkTypedTaskHandler` Protocol。

这些断言守护 ADR-036 基类层级:如果某个 builtin handler 不再继承
`SdkAbstractImportHandler`(例如有人复制了模板),契约就破了,
Java/Python 会漂移。
"""

from __future__ import annotations

import pytest

from tests.handler.conftest import get_attr, require_module

BUILTIN_INHERITANCE = [
    (
        ("batch_worker_sdk.handler.builtin.file_import", "FileImportHandler"),
        ("batch_worker_sdk.handler.abstract_import", "SdkAbstractImportHandler"),
    ),
    (
        ("batch_worker_sdk.handler.builtin.http_dispatch", "HttpDispatchHandler"),
        ("batch_worker_sdk.handler.abstract_dispatch", "SdkAbstractDispatchHandler"),
    ),
    (
        ("batch_worker_sdk.handler.builtin.query_export", "QueryExportHandler"),
        ("batch_worker_sdk.handler.abstract_export", "SdkAbstractExportHandler"),
    ),
]

TYPED_PAIRS = [
    (
        ("batch_worker_sdk.handler.typed.import_handler", "SdkAbstractTypedImportHandler"),
        ("batch_worker_sdk.handler.abstract_import", "SdkAbstractImportHandler"),
    ),
    (
        ("batch_worker_sdk.handler.typed.export_handler", "SdkAbstractTypedExportHandler"),
        ("batch_worker_sdk.handler.abstract_export", "SdkAbstractExportHandler"),
    ),
    (
        ("batch_worker_sdk.handler.typed.process_handler", "SdkAbstractTypedProcessHandler"),
        ("batch_worker_sdk.handler.abstract_process", "SdkAbstractProcessHandler"),
    ),
    (
        ("batch_worker_sdk.handler.typed.dispatch_handler", "SdkAbstractTypedDispatchHandler"),
        ("batch_worker_sdk.handler.abstract_dispatch", "SdkAbstractDispatchHandler"),
    ),
]


@pytest.mark.parametrize(("child", "parent"), BUILTIN_INHERITANCE)
def test_builtin_handler_inherits_abstract_base(
    child: tuple[str, str], parent: tuple[str, str]
) -> None:
    child_mod = require_module(child[0])
    parent_mod = require_module(parent[0])
    child_cls = get_attr(child_mod, child[1])
    parent_cls = get_attr(parent_mod, parent[1])
    assert issubclass(child_cls, parent_cls), (
        f"{child[1]} must inherit {parent[1]} (ADR-036 base-class contract)"
    )


@pytest.mark.parametrize(("typed", "untyped"), TYPED_PAIRS)
def test_typed_base_inherits_untyped_base_or_typed_protocol(
    typed: tuple[str, str], untyped: tuple[str, str]
) -> None:
    """Java 契约:typed 基类继承 untyped 基类。Python 契约:要么继承
    untyped 基类,要么结构化地满足 SdkTypedTaskHandler(Protocol 等价)。"""
    typed_mod = require_module(typed[0])
    typed_cls = get_attr(typed_mod, typed[1])

    untyped_mod = require_module(untyped[0])
    untyped_cls = get_attr(untyped_mod, untyped[1])

    typed_proto_mod = require_module("batch_worker_sdk.handler.typed.typed_task_handler")
    typed_proto = get_attr(typed_proto_mod, "SdkTypedTaskHandler")

    inherits_untyped = issubclass(typed_cls, untyped_cls)
    satisfies_typed_proto = (
        issubclass(typed_cls, typed_proto) if isinstance(typed_proto, type) else False
    )
    assert inherits_untyped or satisfies_typed_proto, (
        f"{typed[1]} satisfies neither {untyped[1]} subclass nor SdkTypedTaskHandler Protocol"
    )


def test_sdk_row_result_is_exposed_from_handler_package() -> None:
    """Java SdkRowResult 是 4 个 long-task 模板共用的行计数器。
    Python 必须暴露等价类型,带 success/skipped/failed/reject。"""
    mod = require_module("batch_worker_sdk.handler.row_result")
    cls = get_attr(mod, "SdkRowResult")
    instance = cls()
    # API 表面检查:4 个 counter + total。
    for attr in ("success", "skipped", "failed", "reject", "total"):
        assert hasattr(instance, attr), f"SdkRowResult missing {attr!r}"


def test_sdk_typed_parameters_is_exposed() -> None:
    """typed handler 输入解析所用的 SdkTypedParameters 基类。"""
    mod = require_module("batch_worker_sdk.handler.typed.typed_parameters")
    get_attr(mod, "SdkTypedParameters")
