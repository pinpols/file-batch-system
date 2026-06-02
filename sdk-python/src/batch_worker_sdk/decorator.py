"""``@batch_task`` decorator — onboarding shortcut.

Mirrors the FastAPI / Spring-starter style declarative registration. A
tenant writes a single ``async def`` and decorates it; the decorator
wraps the function in an :class:`SdkTaskHandler`-compatible object and
appends it to a module-level registry so the platform client can
``collect_registered_handlers()`` at startup.

Java SDK equivalent: ``batch-worker-sdk-spring-boot-starter`` auto
scans ``@BatchTask``-annotated beans. Python keeps it framework-free —
just a module-level list — so it works for both plain ``asyncio`` apps
and FastAPI/Litestar/etc.

Example::

    from batch_worker_sdk import batch_task, SdkTaskContext, SdkTaskResult

    @batch_task("my-job")
    async def my_handler(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({"hello": "world"})
"""

from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable
from typing import Any

from batch_worker_sdk.context import SdkTaskContext
from batch_worker_sdk.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.handler import SdkTaskHandler
from batch_worker_sdk.result import SdkTaskResult

HandlerFn = Callable[[SdkTaskContext], Awaitable[SdkTaskResult]]

_REGISTERED_HANDLERS: list[SdkTaskHandler] = []


class _DecoratedHandler:
    """Concrete :class:`SdkTaskHandler` wrapping a decorated coroutine function.

    Implemented as a plain class (not a closure) so ``isinstance`` and
    ``repr`` show something meaningful in tests / logs. Satisfies the
    runtime-checkable :class:`SdkTaskHandler` protocol structurally;
    no inheritance required.
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

    # Expose the wrapped function so unit tests can call the underlying
    # coroutine directly without going through ``execute``.
    @property
    def wrapped(self) -> HandlerFn:
        return self._fn


def batch_task(
    task_type: str,
    descriptor: SdkTaskTypeDescriptor | None = None,
) -> Callable[[HandlerFn], _DecoratedHandler]:
    """Register an async function as an SDK task handler.

    Args:
        task_type: Globally unique task-type code (must match
            ``job_definition.job_type``). Non-empty after strip.
        descriptor: Optional :class:`SdkTaskTypeDescriptor`; when given
            its ``task_type`` must equal ``task_type`` above (we fail
            fast on mismatch to avoid silent dispatch routing bugs).

    Returns:
        A decorator that wraps the coroutine function in an
        :class:`SdkTaskHandler`-compatible object and side-effects the
        module-level registry. The returned object is what
        :func:`collect_registered_handlers` yields.
    """
    if not isinstance(task_type, str) or not task_type.strip():
        raise ValueError("batch_task: task_type must be a non-empty string")
    if descriptor is not None and descriptor.task_type != task_type:
        raise ValueError(
            f"batch_task: descriptor.task_type={descriptor.task_type!r} "
            f"!= decorator task_type={task_type!r}"
        )

    def _wrap(fn: HandlerFn) -> _DecoratedHandler:
        # Reject sync callables early — the SDK is async-only and a sync
        # function would silently coroutine-wrap with a confusing error.
        if not _is_coroutine_function(fn):
            raise TypeError(
                f"batch_task: handler {fn!r} must be `async def`; the SDK is async-only"
            )
        handler = _DecoratedHandler(task_type=task_type, fn=fn, descriptor=descriptor)
        _REGISTERED_HANDLERS.append(handler)
        return handler

    return _wrap


def collect_registered_handlers() -> list[SdkTaskHandler]:
    """Return a snapshot of all handlers registered via :func:`batch_task`.

    Returns a **copy** — callers cannot mutate the internal registry
    (matches the immutable-snapshot convention from
    :meth:`FakeBatchPlatform.get_reports`).
    """
    return list(_REGISTERED_HANDLERS)


def _clear_registered_handlers() -> None:
    """Reset the registry — **tests only**, leading underscore = private.

    Useful for parametrized tests that need to assert a clean slate; not
    part of the public API.
    """
    _REGISTERED_HANDLERS.clear()


def _is_coroutine_function(fn: Any) -> bool:
    """Thin wrapper around :func:`inspect.iscoroutinefunction`.

    Centralized so tests can monkeypatch one symbol if needed; behaviour
    is identical to a direct call.
    """
    return inspect.iscoroutinefunction(fn)
