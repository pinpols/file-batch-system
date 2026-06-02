"""SdkAbstractProcessHandler 形状的单元测试。"""

from __future__ import annotations

from collections.abc import AsyncIterator

from batch_worker_sdk import SdkAbstractProcessHandler, SdkTaskContext
from batch_worker_sdk.testkit import make_test_context


class _RecordingProcess(SdkAbstractProcessHandler[int, str]):
    def __init__(self, rows: list[int]) -> None:
        self.rows = rows
        self.written: list[str] = []

    def task_type(self) -> str:
        return "process-rec"

    async def _open_input(self, ctx: SdkTaskContext) -> AsyncIterator[int]:  # type: ignore[override]
        for r in self.rows:
            yield r

    async def _transform(self, ctx: SdkTaskContext, input_row: int) -> str | None:
        if input_row % 2 == 0:
            return None  # 偶数跳过
        return f"odd:{input_row}"

    async def _write_output(self, ctx: SdkTaskContext, output_row: str) -> None:
        self.written.append(output_row)


async def test_process_transforms_and_skips() -> None:
    h = _RecordingProcess(rows=[1, 2, 3, 4, 5])
    r = await h.execute(make_test_context())
    assert r.success is True
    assert h.written == ["odd:1", "odd:3", "odd:5"]
    assert r.output["success"] == 3
    assert r.output["skipped"] == 2
    assert r.output["total"] == 5


async def test_process_all_skipped() -> None:
    h = _RecordingProcess(rows=[2, 4, 6])
    r = await h.execute(make_test_context())
    assert r.success is True
    assert h.written == []
    assert r.output["success"] == 0
    assert r.output["skipped"] == 3
