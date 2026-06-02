"""抽象 handler 的 hook 顺序契约。

对 6 个 `SdkAbstract*Handler` 基类(atomic / import / export / process /
dispatch + 共享的 `SdkAbstractTaskHandler`)各自实例化一个最小子类,
让其记录 hook 调用顺序,跑 `execute`,然后断言顺序与 Java 侧
`SdkAbstractTaskHandler` 模板的约定一致(ADR-036)。

hook 名沿用任务规范里的 Python 命名约定(带下划线前缀以示 protected),
是 Java 侧 lowerCamelCase hook 名的 Python 侧投影。
"""

from __future__ import annotations

import asyncio
from typing import Any

from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult
from tests.handler.conftest import get_attr, make_ctx, require_module


def _run(handler: Any, ctx: SdkTaskContext) -> SdkTaskResult:
    """调 execute() —— 同步(Java 风格)/异步都兼容。"""
    result: Any = handler.execute(ctx)
    if asyncio.iscoroutine(result):
        result = asyncio.get_event_loop().run_until_complete(result)
    assert isinstance(result, SdkTaskResult)
    return result


def test_atomic_abstract_calls_do_invoke_exactly_once() -> None:
    mod = require_module("batch_worker_sdk.handler.abstract_atomic")
    base = get_attr(mod, "SdkAbstractAtomicHandler")

    calls: list[str] = []

    class _Echo(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "atomic.echo"

        def _do_invoke(self, ctx: SdkTaskContext) -> Any:
            calls.append("_do_invoke")
            return {"echoed": True}

    result = _run(_Echo(), make_ctx())
    assert calls == ["_do_invoke"]
    assert isinstance(result, SdkTaskResult)
    assert result.success is True


def test_import_abstract_hook_order() -> None:
    mod = require_module("batch_worker_sdk.handler.abstract_import")
    base = get_attr(mod, "SdkAbstractImportHandler")

    calls: list[str] = []

    class _Import(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "import.test"

        def _open_source(self, ctx: SdkTaskContext) -> None:
            calls.append("_open_source")

        def _read_rows(self, ctx: SdkTaskContext):
            calls.append("_read_rows")
            return iter([{"id": 1}, {"id": 2}, {"id": 3}])

        def _load_batch(self, ctx: SdkTaskContext, batch: list[Any]) -> None:
            calls.append(f"_load_batch(n={len(batch)})")

        def _close_source(self, ctx: SdkTaskContext) -> None:
            calls.append("_close_source")

    result = _run(_Import(), make_ctx())
    # 必需顺序:open → read → load(>=1) → close。
    assert calls[0] == "_open_source"
    assert calls[1] == "_read_rows"
    assert any(c.startswith("_load_batch") for c in calls[2:-1])
    assert calls[-1] == "_close_source"
    assert isinstance(result, SdkTaskResult)


def test_export_abstract_hook_order() -> None:
    mod = require_module("batch_worker_sdk.handler.abstract_export")
    base = get_attr(mod, "SdkAbstractExportHandler")

    calls: list[str] = []
    rows_seen: list[Any] = []

    class _Export(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "export.test"

        def _open_destination(self, ctx: SdkTaskContext) -> None:
            calls.append("_open_destination")

        def _query_rows(self, ctx: SdkTaskContext):
            calls.append("_query_rows")
            return iter([{"id": 1}, {"id": 2}])

        def _write_row(self, ctx: SdkTaskContext, row: Any) -> None:
            calls.append("_write_row")
            rows_seen.append(row)

        def _close_destination(self, ctx: SdkTaskContext) -> None:
            calls.append("_close_destination")

    result = _run(_Export(), make_ctx())
    assert calls[0] == "_open_destination"
    assert "_query_rows" in calls
    # _write_row 每行调一次。
    assert calls.count("_write_row") == len(rows_seen) == 2
    assert calls[-1] == "_close_destination"
    assert isinstance(result, SdkTaskResult)


def test_process_abstract_hook_order() -> None:
    mod = require_module("batch_worker_sdk.handler.abstract_process")
    base = get_attr(mod, "SdkAbstractProcessHandler")

    calls: list[str] = []

    class _Process(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "process.test"

        def _open_input(self, ctx: SdkTaskContext):
            calls.append("_open_input")
            return iter([{"v": 1}, {"v": 2}])

        def _transform(self, ctx: SdkTaskContext, row: Any) -> Any:
            calls.append("_transform")
            return {"v": row["v"] * 10}

        def _write_output(self, ctx: SdkTaskContext, batch: list[Any]) -> None:
            calls.append("_write_output")

    result = _run(_Process(), make_ctx())
    assert calls[0] == "_open_input"
    assert calls.count("_transform") == 2
    assert "_write_output" in calls
    # _write_output 必须晚于所有 _transform。
    assert calls.index("_write_output") > max(i for i, c in enumerate(calls) if c == "_transform")
    assert isinstance(result, SdkTaskResult)


def test_dispatch_abstract_hook_order() -> None:
    mod = require_module("batch_worker_sdk.handler.abstract_dispatch")
    base = get_attr(mod, "SdkAbstractDispatchHandler")

    calls: list[str] = []

    class _Dispatch(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "dispatch.test"

        def _resolve_targets(self, ctx: SdkTaskContext) -> list[str]:
            calls.append("_resolve_targets")
            return ["target-a", "target-b", "target-c"]

        def _dispatch_to_target(self, ctx: SdkTaskContext, target: str) -> Any:
            calls.append(f"_dispatch_to_target({target})")
            return {"target": target, "ok": True}

    result = _run(_Dispatch(), make_ctx())
    assert calls[0] == "_resolve_targets"
    # 3 次 fan-out,每次互不重复。
    assert sorted(calls[1:]) == [
        "_dispatch_to_target(target-a)",
        "_dispatch_to_target(target-b)",
        "_dispatch_to_target(target-c)",
    ]
    assert isinstance(result, SdkTaskResult)


def test_base_template_catches_exception_and_returns_failure() -> None:
    """SdkAbstractTaskHandler.execute() 必须捕获 hook 异常并转成
    SdkTaskResult.fail(Java ADR-036 契约)。"""
    mod = require_module("batch_worker_sdk.handler.abstract_atomic")
    base = get_attr(mod, "SdkAbstractAtomicHandler")

    class _Boom(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "atomic.boom"

        def _do_invoke(self, ctx: SdkTaskContext) -> Any:
            raise RuntimeError("kaboom")

    result = _run(_Boom(), make_ctx())
    assert isinstance(result, SdkTaskResult)
    assert result.success is False
    # message 或 output 应该体现底层错误。
    assert "kaboom" in (result.message or "") or "RuntimeError" in (
        result.output.get("errorClass", "") or ""
    )


def test_typed_dispatch_handler_protocol_attached() -> None:
    """4 个 typed 基类也必须满足 SdkTypedTaskHandler Protocol。"""
    typed_mod = require_module("batch_worker_sdk.handler.typed.typed_task_handler")
    typed_protocol = get_attr(typed_mod, "SdkTypedTaskHandler")

    typed_bases = [
        ("batch_worker_sdk.handler.typed.import_handler", "SdkAbstractTypedImportHandler"),
        ("batch_worker_sdk.handler.typed.export_handler", "SdkAbstractTypedExportHandler"),
        ("batch_worker_sdk.handler.typed.process_handler", "SdkAbstractTypedProcessHandler"),
        ("batch_worker_sdk.handler.typed.dispatch_handler", "SdkAbstractTypedDispatchHandler"),
    ]
    for dotted, cls_name in typed_bases:
        mod = require_module(dotted)
        cls = get_attr(mod, cls_name)
        # 走一遍 MRO:typed protocol 要么作为基类,要么通过
        # @runtime_checkable 结构化检查存在。
        is_subclass_or_structural = (
            issubclass(cls, typed_protocol) if isinstance(typed_protocol, type) else True
        )
        assert is_subclass_or_structural, (
            f"{cls_name} does not subclass / satisfy {typed_protocol!r}"
        )
