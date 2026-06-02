"""Tests for :class:`FileImportHandler` — csv / jsonl / empty / missing-file."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

from batch_worker_sdk.handler.builtin import FileImportConfig, FileImportHandler
from batch_worker_sdk.task.context import SdkTaskContext


class _CollectingImportHandler(FileImportHandler):
    """Test subclass: collects every batch into an in-memory list."""

    def __init__(self, config: FileImportConfig) -> None:
        super().__init__(config)
        self.batches: list[list[dict[str, Any]]] = []

    async def _load_batch(self, ctx: SdkTaskContext, batch: list[dict[str, Any]]) -> None:
        # Defensive copy so caller-side mutation doesn't pollute assertions.
        self.batches.append([dict(r) for r in batch])

    @property
    def all_rows(self) -> list[dict[str, Any]]:
        flat: list[dict[str, Any]] = []
        for b in self.batches:
            flat.extend(b)
        return flat


def _ctx(path: str | None) -> SdkTaskContext:
    params: dict[str, Any] = {}
    if path is not None:
        params["filePath"] = path
    return SdkTaskContext(
        tenant_id="t1",
        task_id=1,
        worker_code="w1",
        task_type="import_csv",
        parameters=params,
    )


@pytest.mark.asyncio
async def test_csv_import_with_header(tmp_path: Path) -> None:
    src = tmp_path / "in.csv"
    src.write_text("id,name\n1,Alice\n2,Bob\n", encoding="utf-8")
    cfg = FileImportConfig(task_type="import_csv", batch_size=10)
    handler = _CollectingImportHandler(cfg)

    result = await handler.execute(_ctx(str(src)))

    assert result.success is True
    assert result.output["success"] == 2
    assert handler.all_rows == [
        {"id": "1", "name": "Alice"},
        {"id": "2", "name": "Bob"},
    ]


@pytest.mark.asyncio
async def test_csv_import_chunks_by_batch_size(tmp_path: Path) -> None:
    src = tmp_path / "many.csv"
    lines = ["id,v"] + [f"{i},x{i}" for i in range(1, 6)]
    src.write_text("\n".join(lines) + "\n", encoding="utf-8")
    cfg = FileImportConfig(task_type="import_csv", batch_size=2)
    handler = _CollectingImportHandler(cfg)

    result = await handler.execute(_ctx(str(src)))

    assert result.success is True
    # 5 rows, batch_size=2 → batches of [2, 2, 1]
    assert [len(b) for b in handler.batches] == [2, 2, 1]


@pytest.mark.asyncio
async def test_jsonl_import(tmp_path: Path) -> None:
    src = tmp_path / "in.jsonl"
    src.write_text(
        json.dumps({"id": 1, "name": "Alice"}) + "\n" + json.dumps({"id": 2, "name": "Bob"}) + "\n",
        encoding="utf-8",
    )
    cfg = FileImportConfig(task_type="import_jsonl", format="jsonl", batch_size=10)
    handler = _CollectingImportHandler(cfg)

    result = await handler.execute(_ctx(str(src)))

    assert result.success is True
    assert handler.all_rows == [{"id": 1, "name": "Alice"}, {"id": 2, "name": "Bob"}]


@pytest.mark.asyncio
async def test_empty_file_yields_zero_rows(tmp_path: Path) -> None:
    src = tmp_path / "empty.csv"
    src.write_text("", encoding="utf-8")
    cfg = FileImportConfig(task_type="import_csv", batch_size=10)
    handler = _CollectingImportHandler(cfg)

    result = await handler.execute(_ctx(str(src)))

    assert result.success is True
    assert result.output["success"] == 0
    assert handler.batches == []


@pytest.mark.asyncio
async def test_missing_file_returns_fail(tmp_path: Path) -> None:
    cfg = FileImportConfig(task_type="import_csv")
    handler = _CollectingImportHandler(cfg)

    result = await handler.execute(_ctx(str(tmp_path / "nope.csv")))

    assert result.success is False
    assert result.output.get("errorCode") == "IMPORT_FAILED"


@pytest.mark.asyncio
async def test_missing_file_path_param_returns_fail(tmp_path: Path) -> None:
    cfg = FileImportConfig(task_type="import_csv")
    handler = _CollectingImportHandler(cfg)

    result = await handler.execute(_ctx(None))

    assert result.success is False
    assert "filePath" in (result.message or "")
