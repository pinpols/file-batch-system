"""SdkAbstractExportHandler 形状的单元测试。"""

from __future__ import annotations

from collections.abc import AsyncIterator

from batch_worker_sdk import SdkAbstractExportHandler, SdkTaskContext
from batch_worker_sdk.testkit import make_test_context


class _RecordingExport(SdkAbstractExportHandler[str]):
    def __init__(self, rows: list[str]) -> None:
        self.rows = rows
        self.calls: list[str] = []
        self.written: list[str] = []

    def task_type(self) -> str:
        return "export-rec"

    async def _open_destination(self, ctx: SdkTaskContext) -> None:
        self.calls.append("open")

    async def _query_rows(self, ctx: SdkTaskContext) -> AsyncIterator[str]:  # type: ignore[override]
        for r in self.rows:
            yield r

    async def _write_row(self, ctx: SdkTaskContext, row: str) -> None:
        self.written.append(row)

    async def _close_destination(self, ctx: SdkTaskContext) -> None:
        self.calls.append("close")


async def test_export_writes_each_row_and_counts() -> None:
    h = _RecordingExport(rows=["a", "b", "c"])
    r = await h.execute(make_test_context())
    assert r.success is True
    assert h.written == ["a", "b", "c"]
    assert r.output["success"] == 3
    assert h.calls == ["open", "close"]


async def test_export_empty_source() -> None:
    h = _RecordingExport(rows=[])
    r = await h.execute(make_test_context())
    assert r.success is True
    assert h.written == []
    assert r.output == {"success": 0, "total": 0}
