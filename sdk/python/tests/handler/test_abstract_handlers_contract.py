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
    """调用异步 execute()。"""
    result: Any = handler.execute(ctx)
    if asyncio.iscoroutine(result):
        result = asyncio.run(result)
    assert isinstance(result, SdkTaskResult)
    return result


def test_atomic_abstract_calls_do_invoke_exactly_once() -> None:
    mod = require_module("batch_worker_sdk.handler")
    base = get_attr(mod, "SdkAbstractAtomicHandler")

    calls: list[str] = []

    class _Echo(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "atomic.echo"

        async def _do_invoke(self, ctx: SdkTaskContext) -> Any:
            calls.append("_do_invoke")
            return {"echoed": True}

    result = _run(_Echo(), make_ctx())
    assert calls == ["_do_invoke"]
    assert isinstance(result, SdkTaskResult)
    assert result.success is True


def test_import_abstract_hook_order() -> None:
    mod = require_module("batch_worker_sdk.handler")
    base = get_attr(mod, "SdkAbstractImportHandler")

    calls: list[str] = []

    class _Import(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "import.test"

        async def _open_source(self, ctx: SdkTaskContext) -> None:
            calls.append("_open_source")

        async def _read_rows(self, ctx: SdkTaskContext):
            calls.append("_read_rows")
            for row in ({"id": 1}, {"id": 2}, {"id": 3}):
                yield row

        async def _load_batch(self, ctx: SdkTaskContext, batch: list[Any]) -> None:
            calls.append(f"_load_batch(n={len(batch)})")

        async def _close_source(self, ctx: SdkTaskContext) -> None:
            calls.append("_close_source")

    result = _run(_Import(), make_ctx())
    # 必需顺序:open → read → load(>=1) → close。
    assert calls[0] == "_open_source"
    assert calls[1] == "_read_rows"
    assert any(c.startswith("_load_batch") for c in calls[2:-1])
    assert calls[-1] == "_close_source"
    assert isinstance(result, SdkTaskResult)


def test_export_abstract_hook_order() -> None:
    mod = require_module("batch_worker_sdk.handler")
    base = get_attr(mod, "SdkAbstractExportHandler")

    calls: list[str] = []
    rows_seen: list[Any] = []

    class _Export(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "export.test"

        async def _open_destination(self, ctx: SdkTaskContext) -> None:
            calls.append("_open_destination")

        async def _query_rows(self, ctx: SdkTaskContext):
            calls.append("_query_rows")
            for row in ({"id": 1}, {"id": 2}):
                yield row

        async def _write_row(self, ctx: SdkTaskContext, row: Any) -> None:
            calls.append("_write_row")
            rows_seen.append(row)

        async def _close_destination(self, ctx: SdkTaskContext) -> None:
            calls.append("_close_destination")

    result = _run(_Export(), make_ctx())
    assert calls[0] == "_open_destination"
    assert "_query_rows" in calls
    # _write_row 每行调一次。
    assert calls.count("_write_row") == len(rows_seen) == 2
    assert calls[-1] == "_close_destination"
    assert isinstance(result, SdkTaskResult)


def test_process_abstract_hook_order() -> None:
    mod = require_module("batch_worker_sdk.handler")
    base = get_attr(mod, "SdkAbstractProcessHandler")

    calls: list[str] = []

    class _Process(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "process.test"

        async def _open_input(self, ctx: SdkTaskContext):
            calls.append("_open_input")
            for row in ({"v": 1}, {"v": 2}):
                yield row

        async def _transform(self, ctx: SdkTaskContext, row: Any) -> Any:
            calls.append("_transform")
            return {"v": row["v"] * 10}

        async def _write_output(self, ctx: SdkTaskContext, output: Any) -> None:
            calls.append("_write_output")

    result = _run(_Process(), make_ctx())
    assert calls[0] == "_open_input"
    assert calls.count("_transform") == 2
    assert "_write_output" in calls
    assert calls == [
        "_open_input",
        "_transform",
        "_write_output",
        "_transform",
        "_write_output",
    ]
    assert isinstance(result, SdkTaskResult)


def test_dispatch_abstract_hook_order() -> None:
    mod = require_module("batch_worker_sdk.handler")
    base = get_attr(mod, "SdkAbstractDispatchHandler")

    calls: list[str] = []

    class _Dispatch(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "dispatch.test"

        async def _resolve_targets(self, ctx: SdkTaskContext):
            calls.append("_resolve_targets")
            for target in ("target-a", "target-b", "target-c"):
                yield target

        async def _dispatch_to_target(self, ctx: SdkTaskContext, target: str) -> Any:
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
    mod = require_module("batch_worker_sdk.handler")
    base = get_attr(mod, "SdkAbstractAtomicHandler")

    class _Boom(base):  # type: ignore[misc, valid-type]
        def task_type(self) -> str:
            return "atomic.boom"

        async def _do_invoke(self, ctx: SdkTaskContext) -> Any:
            raise RuntimeError("kaboom")

    result = _run(_Boom(), make_ctx())
    assert isinstance(result, SdkTaskResult)
    assert result.success is False
    # message 或 output 应该体现底层错误。
    assert "kaboom" in (result.message or "") or "RuntimeError" in (
        result.output.get("errorClass", "") or ""
    )


def test_typed_handlers_share_async_template_base() -> None:
    """4 个 typed 基类必须复用统一的异步模板方法。"""
    handler_mod = require_module("batch_worker_sdk.handler")
    template_base = get_attr(handler_mod, "SdkAbstractTaskHandler")

    typed_bases = [
        ("batch_worker_sdk.handler.typed", "SdkAbstractTypedImportHandler"),
        ("batch_worker_sdk.handler.typed", "SdkAbstractTypedExportHandler"),
        ("batch_worker_sdk.handler.typed", "SdkAbstractTypedProcessHandler"),
        ("batch_worker_sdk.handler.typed", "SdkAbstractTypedDispatchHandler"),
    ]
    for dotted, cls_name in typed_bases:
        mod = require_module(dotted)
        cls = get_attr(mod, cls_name)
        assert issubclass(cls, template_base), f"{cls_name} does not reuse the async template"
