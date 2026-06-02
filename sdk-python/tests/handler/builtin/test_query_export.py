"""Tests for :class:`QueryExportHandler` — 1 row / chunked rows / query failure."""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest

from batch_worker_sdk.handler.builtin import QueryExportConfig, QueryExportHandler
from batch_worker_sdk.task.context import SdkTaskContext


class _FakeQueryHandler(QueryExportHandler):
    """Test subclass: yields a configurable in-memory row list."""

    def __init__(
        self,
        config: QueryExportConfig,
        rows: list[dict[str, Any]],
        raise_after: int | None = None,
    ) -> None:
        super().__init__(config)
        self._rows = rows
        self._raise_after = raise_after

    async def _query_rows(self, ctx: SdkTaskContext) -> AsyncIterator[dict[str, Any]]:
        for i, r in enumerate(self._rows):
            if self._raise_after is not None and i >= self._raise_after:
                raise RuntimeError("synthetic query failure")
            yield r


def _ctx(output_path: str) -> SdkTaskContext:
    return SdkTaskContext(
        tenant_id="t1",
        task_id=3,
        worker_code="w1",
        task_type="export_csv",
        parameters={"outputPath": output_path},
    )


@pytest.mark.asyncio
async def test_export_single_row_csv(tmp_path: Path) -> None:
    out = tmp_path / "single.csv"
    cfg = QueryExportConfig(task_type="export_csv", sql="SELECT 1")
    handler = _FakeQueryHandler(cfg, [{"id": 1, "name": "Alice"}])

    result = await handler.execute(_ctx(str(out)))

    assert result.success is True
    assert result.output["success"] == 1
    text = out.read_text(encoding="utf-8")
    # header + one data row
    assert text.splitlines() == ["id,name", "1,Alice"]


@pytest.mark.asyncio
async def test_export_thousand_rows_streamed(tmp_path: Path) -> None:
    out = tmp_path / "many.csv"
    rows = [{"id": i, "v": f"x{i}"} for i in range(1000)]
    cfg = QueryExportConfig(task_type="export_csv", sql="SELECT *", chunk_size=100)
    handler = _FakeQueryHandler(cfg, rows)

    result = await handler.execute(_ctx(str(out)))

    assert result.success is True
    assert result.output["success"] == 1000
    lines = out.read_text(encoding="utf-8").splitlines()
    # 1000 data rows + 1 header
    assert len(lines) == 1001
    assert lines[0] == "id,v"
    assert lines[1] == "0,x0"
    assert lines[-1] == "999,x999"


@pytest.mark.asyncio
async def test_export_query_exception_returns_fail(tmp_path: Path) -> None:
    out = tmp_path / "boom.csv"
    rows = [{"id": i} for i in range(5)]
    cfg = QueryExportConfig(task_type="export_csv", sql="SELECT id")
    handler = _FakeQueryHandler(cfg, rows, raise_after=2)

    result = await handler.execute(_ctx(str(out)))

    assert result.success is False
    assert result.output["errorCode"] == "EXPORT_FAILED"


@pytest.mark.asyncio
async def test_export_jsonl_format(tmp_path: Path) -> None:
    out = tmp_path / "rows.jsonl"
    cfg = QueryExportConfig(task_type="export_jsonl", sql="SELECT 1", format="jsonl")
    handler = _FakeQueryHandler(cfg, [{"a": 1}, {"a": 2, "b": "x"}])

    result = await handler.execute(_ctx(str(out)))

    assert result.success is True
    lines = out.read_text(encoding="utf-8").splitlines()
    assert lines == ['{"a": 1}', '{"a": 2, "b": "x"}']


@pytest.mark.asyncio
async def test_export_missing_output_path_fails(tmp_path: Path) -> None:
    cfg = QueryExportConfig(task_type="export_csv", sql="SELECT 1")
    handler = _FakeQueryHandler(cfg, [])
    ctx = SdkTaskContext(
        tenant_id="t1",
        task_id=3,
        worker_code="w1",
        task_type="export_csv",
        parameters={},
    )

    # tmp_path unused — required by pytest fixture signature only
    _ = tmp_path

    result = await handler.execute(ctx)

    assert result.success is False
    assert "outputPath" in (result.message or "")
