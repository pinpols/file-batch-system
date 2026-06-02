"""Unit tests for SdkAbstractImportHandler shape."""

from __future__ import annotations

from collections.abc import AsyncIterator

from batch_worker_sdk import SdkAbstractImportHandler, SdkTaskContext
from batch_worker_sdk.testkit import make_test_context


class _RecordingImport(SdkAbstractImportHandler[int]):
    """Records hook order; emits ``rows`` ints."""

    def __init__(self, rows: list[int], batch_size_: int = 1000) -> None:
        self.rows = rows
        self._batch_size = batch_size_
        self.calls: list[str] = []
        self.batches: list[list[int]] = []

    def task_type(self) -> str:
        return "import-rec"

    def batch_size(self) -> int:
        return self._batch_size

    async def _open_source(self, ctx: SdkTaskContext) -> None:
        self.calls.append("open")

    async def _read_rows(self, ctx: SdkTaskContext) -> AsyncIterator[int]:  # type: ignore[override]
        self.calls.append("read-start")
        for r in self.rows:
            yield r
        self.calls.append("read-end")

    async def _load_batch(self, ctx: SdkTaskContext, batch: list[int]) -> None:
        self.calls.append(f"load:{len(batch)}")
        self.batches.append(list(batch))

    async def _close_source(self, ctx: SdkTaskContext) -> None:
        self.calls.append("close")


async def test_import_template_order_and_counts() -> None:
    h = _RecordingImport(rows=[1, 2, 3, 4, 5], batch_size_=2)
    r = await h.execute(make_test_context())
    assert r.success is True
    # 5 rows, batch=2 -> flushes of size 2, 2, 1.
    assert h.batches == [[1, 2], [3, 4], [5]]
    assert r.output["success"] == 5
    assert r.output["total"] == 5
    # open precedes first load; close runs in finally.
    assert h.calls[0] == "open"
    assert h.calls[-1] == "close"


async def test_import_empty_source_no_load_calls() -> None:
    h = _RecordingImport(rows=[])
    r = await h.execute(make_test_context())
    assert r.success is True
    assert h.batches == []
    assert r.output == {"success": 0, "total": 0}
    # Source must still be opened and closed.
    assert "open" in h.calls
    assert "close" in h.calls


async def test_import_close_runs_even_on_error() -> None:
    class _Boom(_RecordingImport):
        async def _load_batch(self, ctx: SdkTaskContext, batch: list[int]) -> None:
            self.calls.append("load-raise")
            raise RuntimeError("db down")

    h = _Boom(rows=[1, 2], batch_size_=10)
    r = await h.execute(make_test_context())
    assert r.success is False
    assert "close" in h.calls
