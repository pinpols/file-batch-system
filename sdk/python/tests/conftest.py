"""Top-level test fixtures shared across the sdk-python suite.

The decorator registry in :mod:`batch_worker_sdk.handler._decorator` is
module-level state (``_REGISTERED_HANDLERS: list[...] = []``). Without
a global reset fixture, any test that imports a module which uses
``@batch_task`` at import time would leak handlers into every later
test that calls ``collect_registered_handlers()``.

This autouse fixture clears the registry before and after every test,
giving each test a deterministic empty starting point. Tests that
specifically inspect the registry (``test_decorator.py``,
``handler/test_decorator_isolation.py``) keep working unchanged; tests
that don't care simply ignore it.
"""

from __future__ import annotations

from collections.abc import Iterator

import pytest

from batch_worker_sdk.handler._decorator import _clear_registered_handlers

# Load the optional testkit pytest plugin so the shared `fake_platform` fixture
# is available across the suite (and self-tested). Tenants opt in the same way in
# their own conftest.py: pytest_plugins = ["batch_worker_sdk.testkit.pytest_plugin"].
pytest_plugins = ["batch_worker_sdk.testkit.pytest_plugin"]


@pytest.fixture(autouse=True)
def _reset_handler_registry() -> Iterator[None]:
    """Reset the module-level ``@batch_task`` registry between tests."""
    _clear_registered_handlers()
    yield
    _clear_registered_handlers()
