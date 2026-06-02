"""``@batch_task`` 装饰器 —— 上手快捷方式。

对齐 FastAPI / Spring-starter 的声明式注册风格。租户写一个 ``async def``
并加装饰器,装饰器把函数包成与 :class:`SdkTaskHandler` 兼容的对象,
追加到模块级注册表,平台 client 启动时调 ``collect_registered_handlers()``
拉取即可。

Java SDK 对应物:``batch-worker-sdk-spring-boot-starter`` 自动扫描
``@BatchTask`` 注解 bean。Python 保持框架无关 —— 仅模块级 list —— 因此
裸 ``asyncio`` 应用与 FastAPI/Litestar 等都能用。

示例::

    from batch_worker_sdk import batch_task, SdkTaskContext, SdkTaskResult

    @batch_task("my-job")
    async def my_handler(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({"hello": "world"})
"""

from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable
from typing import Any

from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult

HandlerFn = Callable[[SdkTaskContext], Awaitable[SdkTaskResult]]

_REGISTERED_HANDLERS: list[SdkTaskHandler] = []


class _DecoratedHandler:
    """包装被装饰协程函数的具体 :class:`SdkTaskHandler`。

    用普通类(而非闭包)实现,这样 ``isinstance`` 和 ``repr`` 在测试 / 日志
    里有可读输出。结构化满足运行时可校验的 :class:`SdkTaskHandler`
    protocol,无需继承。
    """

    def __init__(
        self,
        task_type: str,
        fn: HandlerFn,
        descriptor: SdkTaskTypeDescriptor | None,
    ) -> None:
        self._task_type = task_type
        self._fn = fn
        self._descriptor = descriptor

    def task_type(self) -> str:
        return self._task_type

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        return await self._fn(ctx)

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        return self._descriptor

    def cancel(self, ctx: SdkTaskContext) -> None:
        return None

    def __repr__(self) -> str:
        return f"<batch_task handler task_type={self._task_type!r} fn={self._fn.__qualname__}>"

    # 暴露被包装的函数,便于单测直接调底层协程,不必走 ``execute``。
    @property
    def wrapped(self) -> HandlerFn:
        return self._fn


def batch_task(
    task_type: str,
    descriptor: SdkTaskTypeDescriptor | None = None,
) -> Callable[[HandlerFn], _DecoratedHandler]:
    """将一个 async 函数注册为 SDK 任务 handler。

    Args:
        task_type: 全局唯一任务类型码(必须与 ``job_definition.job_type`` 一致)。
            strip 后非空。
        descriptor: 可选 :class:`SdkTaskTypeDescriptor`;给出时其 ``task_type``
            必须等于上面的 ``task_type``(不一致直接 fail-fast,避免静默分派路由 bug)。

    Returns:
        装饰器,将协程函数包成 :class:`SdkTaskHandler` 兼容对象,并副作用地
        登记到模块级注册表。返回的对象即 :func:`collect_registered_handlers`
        所产出的元素。
    """
    if not isinstance(task_type, str) or not task_type.strip():
        raise ValueError("batch_task: task_type must be a non-empty string")
    if descriptor is not None and descriptor.task_type != task_type:
        raise ValueError(
            f"batch_task: descriptor.task_type={descriptor.task_type!r} "
            f"!= decorator task_type={task_type!r}"
        )

    def _wrap(fn: HandlerFn) -> _DecoratedHandler:
        # 提前拒绝同步可调用对象 —— SDK 仅异步,同步函数被静默包成协程时
        # 错误信息会非常迷惑。
        if not _is_coroutine_function(fn):
            raise TypeError(
                f"batch_task: handler {fn!r} must be `async def`; the SDK is async-only"
            )
        handler = _DecoratedHandler(task_type=task_type, fn=fn, descriptor=descriptor)
        _REGISTERED_HANDLERS.append(handler)
        return handler

    return _wrap


def collect_registered_handlers() -> list[SdkTaskHandler]:
    """返回所有通过 :func:`batch_task` 注册的 handler 的快照。

    返回**副本** —— 调用方无法改动内部注册表(与
    :meth:`FakeBatchPlatform.get_reports` 的不可变快照约定一致)。
    """
    return list(_REGISTERED_HANDLERS)


def _clear_registered_handlers() -> None:
    """重置注册表 —— **仅测试用**,前缀下划线表示私有。

    便于参数化测试断言一个干净起点;不属于公开 API。
    """
    _REGISTERED_HANDLERS.clear()


def _is_coroutine_function(fn: Any) -> bool:
    """对 :func:`inspect.iscoroutinefunction` 的薄封装。

    集中一处便于测试 monkeypatch 单一符号;行为与直接调用一致。
    """
    return inspect.iscoroutinefunction(fn)
