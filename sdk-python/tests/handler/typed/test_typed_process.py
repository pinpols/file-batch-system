"""Tests for ``SdkAbstractTypedProcessHandler``."""

from __future__ import annotations

import pytest
from pydantic import BaseModel

from batch_worker_sdk.handler.typed import SdkAbstractTypedProcessHandler

from ._helpers import make_ctx


class _Req(BaseModel):
    only_even: bool = True


class _MyProcess(
    SdkAbstractTypedProcessHandler[_Req, dict, dict, BaseModel]
):
    def __init__(self) -> None:
        self.upserts: list[list[dict]] = []

    def task_type(self) -> str:
        return "proc"

    def select_input(self, params, ctx):  # type: ignore[override]
        for i in range(5):
            yield {"i": i}

    def transform(self, params, ctx, row):  # type: ignore[override]
        if params.only_even and row["i"] % 2 != 0:
            return None  # skip odd
        return {"out": row["i"] * 10}

    def upsert(self, params, ctx, batch):  # type: ignore[override]
        self.upserts.append(list(batch))

    def batch_size(self) -> int:
        return 2


@pytest.mark.asyncio
async def test_typed_process_skips_when_transform_returns_none() -> None:
    h = _MyProcess()
    r = await h.execute(make_ctx({"only_even": True}))
    # 0,2,4 -> 3 outputs; batch_size 2 -> [2,1]
    assert [len(b) for b in h.upserts] == [2, 1]
    assert r.success is True
    assert r.output["success"] == 3
    assert r.output["skipped"] == 2
    assert r.output["total"] == 5


@pytest.mark.asyncio
async def test_typed_process_invalid_params() -> None:
    class _Strict(BaseModel):
        required: str

    class _H(SdkAbstractTypedProcessHandler[_Strict, dict, dict, BaseModel]):
        def task_type(self) -> str:
            return "p2"

        def select_input(self, params, ctx):  # type: ignore[override]
            yield {}

        def transform(self, params, ctx, row):  # type: ignore[override]
            return row

        def upsert(self, params, ctx, batch):  # type: ignore[override]
            pass

    r = await _H().execute(make_ctx({}))
    assert r.success is False
    assert r.output["errorCode"] == "INVALID_TYPED_PARAMS"
