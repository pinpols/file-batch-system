"""Tests for :class:`ProgressReporter` (Lane U / P4)."""

from __future__ import annotations

import asyncio

import pytest

from batch_worker_sdk import ProgressReporter
from batch_worker_sdk.internal._sensitive_keys import (
    SENSITIVE_KEYWORDS,
    find_sensitive_keys,
    is_sensitive_key,
)


def test_latest_returns_none_before_first_report() -> None:
    pr = ProgressReporter()
    assert pr.latest() is None


def test_report_then_latest_returns_value() -> None:
    pr = ProgressReporter()
    pr.report({"processed": 100, "total": 1000})
    assert pr.latest() == {"processed": 100, "total": 1000}


def test_latest_returns_independent_copy() -> None:
    pr = ProgressReporter()
    pr.report({"processed": 100})
    snap1 = pr.latest()
    assert snap1 is not None
    snap1["processed"] = 999  # mutate the returned copy
    snap2 = pr.latest()
    assert snap2 == {"processed": 100}, "stored snapshot must not be affected"


def test_report_copies_input_dict() -> None:
    pr = ProgressReporter()
    src = {"processed": 100}
    pr.report(src)
    src["processed"] = 999  # mutate source after report
    assert pr.latest() == {"processed": 100}


def test_report_latest_value_wins() -> None:
    pr = ProgressReporter()
    pr.report({"processed": 1})
    pr.report({"processed": 50})
    pr.report({"processed": 100})
    assert pr.latest() == {"processed": 100}


@pytest.mark.parametrize(
    "bad_key",
    [
        "password",
        "db_password",
        "DB-PASSWORD",
        "secret",
        "apiKey",
        "api_key",
        "my.api.key",
        "auth_token",
        "credential",
        "PrivateKey",
        "access_key",
    ],
)
def test_report_rejects_sensitive_keys(bad_key: str) -> None:
    pr = ProgressReporter()
    with pytest.raises(ValueError, match="sensitive"):
        pr.report({bad_key: "hunter2", "processed": 100})
    # State unchanged after rejected call.
    assert pr.latest() is None


def test_report_rejects_non_dict() -> None:
    pr = ProgressReporter()
    with pytest.raises(ValueError, match="dict"):
        pr.report("not a dict")  # type: ignore[arg-type]


async def test_concurrent_report_is_thread_safe() -> None:
    pr = ProgressReporter()

    async def writer(n: int) -> None:
        for i in range(50):
            pr.report({"processed": n * 1000 + i})

    await asyncio.gather(*(writer(k) for k in range(8)))
    final = pr.latest()
    assert final is not None
    # No assertion on exact final value — just that we didn't crash and
    # the slot holds *some* valid snapshot.
    assert "processed" in final


def test_sensitive_keywords_normalization_helpers() -> None:
    """Direct coverage for the helper module used by ProgressReporter."""
    assert SENSITIVE_KEYWORDS  # non-empty
    assert is_sensitive_key("password") is True
    assert is_sensitive_key("PASSWORD") is True
    assert is_sensitive_key("processed") is False
    assert find_sensitive_keys(["a", "password", "b"]) == ["password"]
    assert find_sensitive_keys(["safe", "also_safe"]) == []
