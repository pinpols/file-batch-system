"""Tests for ``SdkAbstractTypedDispatchHandler``."""

from __future__ import annotations

import pytest
from pydantic import BaseModel

from batch_worker_sdk.handler.typed import SdkAbstractTypedDispatchHandler

from ._helpers import make_ctx


class _Req(BaseModel):
    endpoint: str


class _MyDispatch(SdkAbstractTypedDispatchHandler[_Req, BaseModel, dict]):
    def __init__(self, fail_indices: set[int] | None = None) -> None:
        self.fail_indices = fail_indices or set()
        self.responses_seen: list[object] = []

    def task_type(self) -> str:
        return "disp"

    def select_payload(self, params, ctx):  # type: ignore[override]
        return [{"i": i} for i in range(4)]

    def build_request(self, params, ctx, item):  # type: ignore[override]
        return {"to": params.endpoint, **item}

    def push(self, params, ctx, request):  # type: ignore[override]
        if request["i"] in self.fail_indices:
            raise RuntimeError(f"network down for {request['i']}")
        return {"ok": True, "i": request["i"]}

    def on_response(self, params, ctx, item, response):  # type: ignore[override]
        self.responses_seen.append(response)


@pytest.mark.asyncio
async def test_typed_dispatch_all_success() -> None:
    h = _MyDispatch()
    r = await h.execute(make_ctx({"endpoint": "http://x"}))
    assert r.success is True
    assert r.output["success"] == 4
    assert r.output["total"] == 4
    assert len(h.responses_seen) == 4


@pytest.mark.asyncio
async def test_typed_dispatch_partial_failure_continues_batch() -> None:
    h = _MyDispatch(fail_indices={1, 3})
    r = await h.execute(make_ctx({"endpoint": "http://x"}))
    # Per-item failures don't abort — counter records 2 success + 2 failed.
    assert r.success is True
    assert r.output["success"] == 2
    assert r.output["failed"] == 2
    assert r.output["total"] == 4
    assert len(h.responses_seen) == 2  # only successes hit on_response


@pytest.mark.asyncio
async def test_typed_dispatch_invalid_params() -> None:
    r = await _MyDispatch().execute(make_ctx({}))
    assert r.success is False
    assert r.output["errorCode"] == "INVALID_TYPED_PARAMS"
