"""Tests for ``SdkAbstractTypedImportHandler``."""

from __future__ import annotations

import pytest
from pydantic import BaseModel

from batch_worker_sdk.handler.typed import SdkAbstractTypedImportHandler

from ._helpers import make_ctx


class _ImportReq(BaseModel):
    source: str


class _Result(BaseModel):
    imported: int


class _MyImport(
    SdkAbstractTypedImportHandler[_ImportReq, _Result, dict]
):
    DEFAULT_BATCH_SIZE = 2

    def __init__(self) -> None:
        self.opened: bool = False
        self.batches: list[list[dict]] = []

    def task_type(self) -> str:
        return "imp"

    def open_source(self, params, ctx):  # type: ignore[override]
        self.opened = True

    def read_rows(self, params, ctx):  # type: ignore[override]
        for i in range(5):
            yield {"i": i, "src": params.source}

    def load_batch(self, params, ctx, batch):  # type: ignore[override]
        self.batches.append(list(batch))

    def batch_size(self) -> int:
        return 2

    def summarize(self, params, counts):  # type: ignore[override]
        return _Result(imported=counts.success)


@pytest.mark.asyncio
async def test_typed_import_template_flow() -> None:
    h = _MyImport()
    r = await h.execute(make_ctx({"source": "/tmp/x.csv"}))
    assert h.opened is True
    # 5 rows, batch_size 2 -> 2+2+1
    assert [len(b) for b in h.batches] == [2, 2, 1]
    assert r.success is True
    assert r.output == {"imported": 5}


@pytest.mark.asyncio
async def test_typed_import_invalid_params() -> None:
    h = _MyImport()
    r = await h.execute(make_ctx({}))
    assert r.success is False
    assert "source" in (r.message or "")
    assert r.output["errorCode"] == "INVALID_TYPED_PARAMS"


@pytest.mark.asyncio
async def test_typed_import_no_summarize_falls_back_to_counter() -> None:
    class _Bare(SdkAbstractTypedImportHandler[_ImportReq, _Result, dict]):
        def task_type(self) -> str:
            return "imp2"

        def read_rows(self, params, ctx):  # type: ignore[override]
            yield {"k": 1}

        def load_batch(self, params, ctx, batch):  # type: ignore[override]
            pass

    r = await _Bare().execute(make_ctx({"source": "x"}))
    assert r.success is True
    assert r.output["success"] == 1
    assert r.output["total"] == 1
