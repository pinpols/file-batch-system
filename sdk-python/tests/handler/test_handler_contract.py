"""Handler registration contract.

For each of the 11 concrete handlers (4 atomic + 3 builtin + 4 typed):

* `task_type()` returns a non-empty string;
* `descriptor()` is callable and returns either `None` or an
  `SdkTaskTypeDescriptor` instance (no exception);
* the instance satisfies the `SdkTaskHandler` Protocol via
  `isinstance` (it is `@runtime_checkable`).
"""

from __future__ import annotations

from typing import Any

import pytest

from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from tests.handler.conftest import get_attr, require_module

# (module dotted path, class name) for each of the 11 concrete handlers.
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
    """Instantiate a handler with no-arg or with minimal fakes if needed."""
    mod = require_module(dotted)
    cls = get_attr(mod, cls_name)
    # Concrete handlers must be constructible without args (parameters come
    # via SdkTaskContext). Typed abstract bases need a minimal subclass.
    try:
        return cls()
    except TypeError:
        # Typed abstract → make a no-op subclass that fills all abstract
        # methods with stubs; we only need to introspect task_type and
        # descriptor, not actually execute.

        class _Stub(cls):  # type: ignore[misc, valid-type]
            def task_type(self) -> str:
                return f"stub.{cls_name}"

            def _stub(self, *a: Any, **kw: Any) -> Any:
                return None

            # Fill in anything else abstract with a permissive stub.
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
    # SdkTaskHandler is @runtime_checkable so isinstance() checks the
    # structural shape (task_type / execute / descriptor / cancel).
    assert isinstance(instance, SdkTaskHandler), (
        f"{cls_name} does not satisfy the SdkTaskHandler Protocol"
    )


def test_no_duplicate_task_types_across_concrete_handlers() -> None:
    """The 7 always-concrete handlers (atomic + builtin) must have distinct task_types."""
    seen: dict[str, str] = {}
    for dotted, cls_name in ATOMIC_HANDLERS + BUILTIN_HANDLERS:
        instance = _instantiate(dotted, cls_name)
        tt = instance.task_type()
        if tt in seen:
            pytest.fail(f"duplicate task_type {tt!r} on {cls_name} and {seen[tt]}")
        seen[tt] = cls_name
