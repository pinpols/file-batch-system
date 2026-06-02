"""Shared fixtures + soft-import helpers for handler contract tests.

The 4 sibling feature branches (abstract-base / atomic / builtin / typed)
land independently. Until all 4 are on main, individual concrete handler
modules may be absent. We expose `try_import` so each test module can
short-circuit cleanly (pytest.skip) when its dependency is missing.
"""

from __future__ import annotations

import importlib
from types import ModuleType
from typing import Any

import pytest

from batch_worker_sdk.task.context import SdkTaskContext


def try_import(dotted: str) -> ModuleType | None:
    """Best-effort import: returns None instead of raising ImportError.

    Used by handler contract tests so that a missing sibling-branch
    module skips its tests rather than failing the whole suite.
    """
    try:
        return importlib.import_module(dotted)
    except ImportError:
        return None


def require_module(dotted: str) -> ModuleType:
    """Import or pytest.skip — call from inside a test."""
    mod = try_import(dotted)
    if mod is None:
        pytest.skip(f"dependency module {dotted!r} not yet merged")
    return mod


def get_attr(module: ModuleType, name: str) -> Any:
    """Read an attribute from a sibling-branch module or skip the test."""
    if not hasattr(module, name):
        pytest.skip(f"{module.__name__!r} has no attribute {name!r} (sibling branch incomplete)")
    return getattr(module, name)


@pytest.fixture
def base_ctx() -> SdkTaskContext:
    """A minimal SdkTaskContext suitable for hook-order assertions."""
    return SdkTaskContext(
        tenant_id="t-1",
        task_id=42,
        worker_code="worker-py-1",
        task_type="contract-test",
        parameters={},
        runtime_attributes={},
    )


def make_ctx(**overrides: Any) -> SdkTaskContext:
    """Build an SdkTaskContext with overrideable fields."""
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
