"""SdkRowResult 计数器 / to_output 渲染的单元测试。"""

from __future__ import annotations

from batch_worker_sdk import SdkRowResult


def test_counters_start_zero_and_total_sums() -> None:
    r = SdkRowResult()
    assert r.success() == 0
    assert r.total() == 0
    r.inc_success()
    r.inc_success()
    r.inc_skipped()
    r.inc_failed()
    r.inc_reject()
    assert r.success() == 2
    assert r.skipped() == 1
    assert r.failed() == 1
    assert r.reject() == 1
    assert r.total() == 5


def test_add_success_bulk_increment() -> None:
    r = SdkRowResult()
    r.add_success(100)
    r.add_success(50)
    assert r.success() == 150


def test_to_output_omits_zero_buckets() -> None:
    r = SdkRowResult()
    r.add_success(3)
    out = r.to_output()
    assert out == {"success": 3, "total": 3}
    # zero 时不输出 skipped / failed / reject 这些 key。
    assert "skipped" not in out
    assert "failed" not in out
    assert "reject" not in out


def test_to_output_includes_nonzero_buckets() -> None:
    r = SdkRowResult()
    r.add_success(2)
    r.inc_skipped()
    r.inc_failed()
    r.inc_reject()
    out = r.to_output()
    assert out == {
        "success": 2,
        "skipped": 1,
        "failed": 1,
        "reject": 1,
        "total": 5,
    }
