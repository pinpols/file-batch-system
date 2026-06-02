"""Tests for ``SdkAbstractTypedExportHandler``."""

from __future__ import annotations

import pytest
from pydantic import BaseModel

from batch_worker_sdk.handler.typed import SdkAbstractTypedExportHandler
from batch_worker_sdk.task.result import SdkTaskResult

from ._helpers import make_ctx


class _Req(BaseModel):
    table: str


class _Out(BaseModel):
    rows: int


class _MyExport(SdkAbstractTypedExportHandler[_Req, _Out, dict]):
    def __init__(self) -> None:
        self.sink_opened = False
        self.formatted: list[dict] = []

    def task_type(self) -> str:
        return "exp"

    def open_sink(self, params, ctx):  # type: ignore[override]
        self.sink_opened = True

    def build_query(self, params, ctx) -> str:  # type: ignore[override]
        return f"SELECT * FROM {params.table}"

    def stream_rows(self, params, ctx, query):  # type: ignore[override]
        yield {"q": query, "i": 0}
        yield {"q": query, "i": 1}

    def format_row(self, params, ctx, row) -> None:  # type: ignore[override]
        self.formatted.append(row)

    def summarize(self, params, counts):  # type: ignore[override]
        return _Out(rows=counts.success())


@pytest.mark.asyncio
async def test_typed_export_template_flow() -> None:
    h = _MyExport()
    r = await h.execute(make_ctx({"table": "orders"}))
    assert h.sink_opened is True
    assert len(h.formatted) == 2
    assert h.formatted[0]["q"] == "SELECT * FROM orders"
    assert r.success is True
    assert r.output == {"rows": 2}


@pytest.mark.asyncio
async def test_typed_export_write_out_wins_over_summarize() -> None:
    class _ExplicitResult(_MyExport):
        def write_out(self, params, ctx, counts):  # type: ignore[override]
            return SdkTaskResult.success_with(
                output={"custom": True, "n": counts.success()}, message="custom finalize"
            )

    r = await _ExplicitResult().execute(make_ctx({"table": "t"}))
    assert r.message == "custom finalize"
    assert r.output == {"custom": True, "n": 2}
