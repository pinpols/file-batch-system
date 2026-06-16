"""Verifies the global autouse fixture isolates the ``@batch_task`` registry.

The decorator pushes onto module-level ``_REGISTERED_HANDLERS``. The
top-level :mod:`tests.conftest` autouse fixture must reset that list
before and after every test, otherwise registrations leak across tests
in import order and cause flaky behaviour.

These three tests are intentionally written so a *missing* reset
fixture would fail at least one of them: Test 1 registers ``foo``;
Test 2 again registers a same-name handler and asserts count == 1 (not
2), which only holds if the registry was cleared between tests.
"""

from __future__ import annotations

import pytest

from batch_worker_sdk import (
    SdkTaskContext,
    SdkTaskResult,
    batch_task,
    collect_registered_handlers,
)


def test_single_registration_starts_clean() -> None:
    @batch_task("foo")
    async def foo_handler(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    handlers = collect_registered_handlers()
    assert len(handlers) == 1
    assert handlers[0].task_type() == "foo"


def test_re_registration_in_separate_test_is_isolated() -> None:
    # If the autouse fixture didn't reset the registry, the previous
    # test's "foo" handler would still be present and len() would be 2.
    @batch_task("foo")
    async def foo_handler_again(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    handlers = collect_registered_handlers()
    assert len(handlers) == 1, (
        "Registry leaked across tests — top-level conftest fixture is broken."
    )
    assert handlers[0].task_type() == "foo"


def test_duplicate_task_type_within_one_test_appends() -> None:
    """Document current behaviour: same task_type registered twice in the
    same test produces two entries (no de-duplication).

    Today's :func:`batch_task` is append-only; if a future change
    decides duplicates should raise ``ValueError``, this test will fail
    and force an intentional update — i.e. it pins behaviour to prevent
    silent drift either way.
    """

    @batch_task("foo")
    async def first(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    @batch_task("foo")
    async def second(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    handlers = collect_registered_handlers()
    assert len(handlers) == 2
    assert {h.task_type() for h in handlers} == {"foo"}


def test_collect_returns_snapshot_not_live_view() -> None:
    @batch_task("snap")
    async def h(ctx: SdkTaskContext) -> SdkTaskResult:
        return SdkTaskResult.success_with({})

    snapshot = collect_registered_handlers()
    snapshot.clear()
    # Mutating the returned list must not affect the internal registry.
    assert len(collect_registered_handlers()) == 1


if __name__ == "__main__":  # pragma: no cover
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
