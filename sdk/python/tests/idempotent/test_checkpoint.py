"""ADR-037 断点续跑 / 可靠提交 / 协作取消(Task B)单测。

覆盖 P1(checkpoint 协议 + state)、P2(ctx.commit 三合一 + 限流上报 + selfReport)、
P3(SdkTaskStopped 安全点)+ typed 模板织入(resume / commit / cancel)。
"""

from __future__ import annotations

import pytest
from pydantic import BaseModel

from batch_worker_sdk.exceptions import SdkTaskStopped
from batch_worker_sdk.handler._base import CANCELLED_CODE
from batch_worker_sdk.handler.typed import (
    SdkAbstractTypedExportHandler,
    SdkAbstractTypedImportHandler,
    SdkAbstractTypedProcessHandler,
)
from batch_worker_sdk.task.cancellation import CancellationSignal
from batch_worker_sdk.task.checkpoint import (
    InMemoryCheckpoint,
    SdkCheckpoint,
    SdkCheckpointState,
)
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.progress import ProgressReporter


def make_ctx(
    *,
    parameters: dict | None = None,
    checkpoint: SdkCheckpoint | None = None,
    cancel: CancellationSignal | None = None,
    reporter: ProgressReporter | None = None,
    report_interval_batches: int = 1,
    self_report: bool = True,
    task_id: int = 42,
) -> SdkTaskContext:
    return SdkTaskContext(
        tenant_id="t1",
        task_id=task_id,
        worker_code="w-1",
        task_type="typed-test",
        parameters=parameters or {},
        checkpoint_store=checkpoint,
        cancel_signal=cancel,
        progress_reporter=reporter,
        report_interval_batches=report_interval_batches,
        self_report=self_report,
    )


# ===== P1: SdkCheckpointState / SdkCheckpoint =====================


def test_checkpoint_state_defaults_and_defensive_copy() -> None:
    src = {"id": 5}
    st = SdkCheckpointState(break_position=src, succeed_count=3)
    src["id"] = 99  # 改原 dict 不污染快照
    assert st.break_position == {"id": 5}
    assert st.succeed_count == 3
    assert st.fail_count == 0
    assert st.completed is False


def test_in_memory_checkpoint_load_save() -> None:
    cp = InMemoryCheckpoint()
    assert cp.load(1) is None
    cp.save(1, SdkCheckpointState(break_position={"id": 7}, succeed_count=7))
    got = cp.load(1)
    assert got is not None
    assert got.break_position == {"id": 7}


def test_in_memory_checkpoint_satisfies_protocol() -> None:
    assert isinstance(InMemoryCheckpoint(), SdkCheckpoint)


# ===== P2: ctx.commit ============================================


@pytest.mark.asyncio
async def test_commit_saves_checkpoint_and_counts() -> None:
    cp = InMemoryCheckpoint()
    ctx = make_ctx(checkpoint=cp)
    await ctx.commit({"id": 100}, succeed_count=100, fail_count=2)
    st = cp.load(42)
    assert st is not None
    assert st.break_position == {"id": 100}
    assert st.succeed_count == 100
    assert st.fail_count == 2


@pytest.mark.asyncio
async def test_commit_progress_rate_limited() -> None:
    reporter = ProgressReporter()
    ctx = make_ctx(reporter=reporter, report_interval_batches=2)
    await ctx.commit({"id": 1}, succeed_count=1)
    assert reporter.latest() is None  # 第 1 次:1 % 2 != 0
    await ctx.commit({"id": 2}, succeed_count=2)
    snap = reporter.latest()
    assert snap == {"succeed": 2, "failed": 0, "breakPosition": {"id": 2}}


@pytest.mark.asyncio
async def test_commit_self_report_off_skips_progress() -> None:
    reporter = ProgressReporter()
    ctx = make_ctx(reporter=reporter, self_report=False)
    await ctx.commit({"id": 1}, succeed_count=1)
    assert reporter.latest() is None


@pytest.mark.asyncio
async def test_checkpoint_fallback_when_not_injected() -> None:
    ctx = make_ctx()
    cp = ctx.checkpoint()
    assert isinstance(cp, InMemoryCheckpoint)
    # 同一 ctx 多次取到同一个回落实例
    assert ctx.checkpoint() is cp


# ===== P3: SdkTaskStopped safe-point ==============================


@pytest.mark.asyncio
async def test_commit_raises_stopped_when_cancelled() -> None:
    cp = InMemoryCheckpoint()
    sig = CancellationSignal()
    sig.mark_cancelled()
    ctx = make_ctx(checkpoint=cp, cancel=sig)
    with pytest.raises(SdkTaskStopped) as ei:
        await ctx.commit({"id": 50}, succeed_count=50)
    # 断点在抛之前已落盘(安全点语义)
    assert cp.load(42).break_position == {"id": 50}
    assert ei.value.break_position == {"id": 50}


@pytest.mark.asyncio
async def test_commit_no_stop_when_not_cancelled() -> None:
    ctx = make_ctx()
    await ctx.commit({"id": 1})  # 不抛


def test_is_cancelled_without_signal_is_false() -> None:
    assert make_ctx().is_cancelled() is False


# ===== typed import 织入 =========================================


class _Req(BaseModel):
    source: str


class _ResumableImport(SdkAbstractTypedImportHandler[_Req, BaseModel, dict]):
    DEFAULT_BATCH_SIZE = 2

    def __init__(self, cancel_after_first: CancellationSignal | None = None) -> None:
        self.loaded: list[list[dict]] = []
        self.start_from: dict = {}
        self._cancel_after_first = cancel_after_first

    def task_type(self) -> str:
        return "imp"

    def read_rows(self, params, ctx):
        self.start_from = self.resume_from(ctx)
        start = self.start_from.get("i", -1)
        for i in range(5):
            if i <= start:
                continue
            yield {"i": i}

    def load_batch(self, params, ctx, batch):
        self.loaded.append(list(batch))
        # 第一批写完后请求取消 -> 触发该批 commit 的安全点。
        if self._cancel_after_first is not None and len(self.loaded) == 1:
            self._cancel_after_first.mark_cancelled()

    def checkpoint_break_key(self, ctx, last_row):
        return {"i": last_row["i"]}


@pytest.mark.asyncio
async def test_typed_import_commits_per_batch() -> None:
    cp = InMemoryCheckpoint()
    h = _ResumableImport()
    r = await h.execute(make_ctx(parameters={"source": "x"}, checkpoint=cp))
    assert r.success is True
    # 5 行 batch 2 -> [2,2,1];最后断点 i=4
    assert [len(b) for b in h.loaded] == [2, 2, 1]
    assert cp.load(42).break_position == {"i": 4}


@pytest.mark.asyncio
async def test_typed_import_resume_skips_done_rows() -> None:
    cp = InMemoryCheckpoint()
    # 预置:已处理到 i=2,未完成
    cp.save(42, SdkCheckpointState(break_position={"i": 2}, succeed_count=3))
    h = _ResumableImport()
    r = await h.execute(make_ctx(parameters={"source": "x"}, checkpoint=cp))
    assert r.success is True
    # 只读 i=3,4 这两行
    assert h.start_from == {"i": 2}
    assert [row for b in h.loaded for row in b] == [{"i": 3}, {"i": 4}]
    # 计数恢复:3(续) + 2(新)
    assert cp.load(42).succeed_count == 5


@pytest.mark.asyncio
async def test_typed_import_completed_skips_entirely() -> None:
    cp = InMemoryCheckpoint()
    cp.save(42, SdkCheckpointState(succeed_count=5, completed=True))
    h = _ResumableImport()
    r = await h.execute(make_ctx(parameters={"source": "x"}, checkpoint=cp))
    assert r.success is True
    assert h.loaded == []  # 一行没读
    assert r.output["success"] == 5


@pytest.mark.asyncio
async def test_typed_import_cancel_yields_cancelled_terminal() -> None:
    cp = InMemoryCheckpoint()
    sig = CancellationSignal()  # 执行中(第一批后)才置位
    h = _ResumableImport(cancel_after_first=sig)
    r = await h.execute(make_ctx(parameters={"source": "x"}, checkpoint=cp, cancel=sig))
    assert r.success is False
    assert r.output["errorCode"] == CANCELLED_CODE
    # 第一批已安全提交(i=1)
    assert cp.load(42).break_position == {"i": 1}
    assert h.loaded == [[{"i": 0}, {"i": 1}]]


# ===== typed process 织入 ========================================


class _ProcReq(BaseModel):
    pass


class _ResumableProcess(SdkAbstractTypedProcessHandler[_ProcReq, dict, dict, BaseModel]):
    DEFAULT_BATCH_SIZE = 2

    def __init__(self, cancel_after_first: CancellationSignal | None = None) -> None:
        self.upserts: list[list[dict]] = []
        self._cancel_after_first = cancel_after_first

    def task_type(self) -> str:
        return "proc"

    def select_input(self, params, ctx):
        for i in range(4):
            yield {"i": i}

    def transform(self, params, ctx, row):
        return {"o": row["i"]}

    def upsert(self, params, ctx, batch):
        self.upserts.append(list(batch))
        if self._cancel_after_first is not None and len(self.upserts) == 1:
            self._cancel_after_first.mark_cancelled()

    def checkpoint_break_key(self, ctx, last_row):
        return {"o": last_row["o"]}


@pytest.mark.asyncio
async def test_typed_process_commits_per_drain() -> None:
    cp = InMemoryCheckpoint()
    h = _ResumableProcess()
    r = await h.execute(make_ctx(checkpoint=cp))
    assert r.success is True
    assert [len(b) for b in h.upserts] == [2, 2]
    assert cp.load(42).break_position == {"o": 3}


@pytest.mark.asyncio
async def test_typed_process_cancel_terminal() -> None:
    cp = InMemoryCheckpoint()
    sig = CancellationSignal()
    h = _ResumableProcess(cancel_after_first=sig)
    r = await h.execute(make_ctx(checkpoint=cp, cancel=sig))
    assert r.success is False
    assert r.output["errorCode"] == CANCELLED_CODE
    assert h.upserts == [[{"o": 0}, {"o": 1}]]


# ===== typed export 织入 =========================================


class _ExpReq(BaseModel):
    pass


class _ResumableExport(SdkAbstractTypedExportHandler[_ExpReq, BaseModel, dict]):
    DEFAULT_COMMIT_INTERVAL_ROWS = 2

    def __init__(self, cancel_after_first_commit: CancellationSignal | None = None) -> None:
        self.written: list[dict] = []
        self._cancel = cancel_after_first_commit

    def task_type(self) -> str:
        return "exp"

    def build_query(self, params, ctx):
        return "select 1"

    def stream_rows(self, params, ctx, query):
        for i in range(5):
            yield {"i": i}

    def format_row(self, params, ctx, row):
        self.written.append(row)
        # 写满第一个 interval(2 行)后置位 -> 该批 commit 命中安全点。
        if self._cancel is not None and len(self.written) == 2:
            self._cancel.mark_cancelled()

    def checkpoint_break_key(self, ctx, last_row):
        return {"i": last_row["i"]}


@pytest.mark.asyncio
async def test_typed_export_commits_at_interval() -> None:
    cp = InMemoryCheckpoint()
    h = _ResumableExport()
    r = await h.execute(make_ctx(checkpoint=cp))
    assert r.success is True
    assert len(h.written) == 5
    # interval 2 -> commit 在第 2、4 行后;最后断点 i=3
    assert cp.load(42).break_position == {"i": 3}


@pytest.mark.asyncio
async def test_typed_export_cancel_terminal() -> None:
    cp = InMemoryCheckpoint()
    sig = CancellationSignal()
    h = _ResumableExport(cancel_after_first_commit=sig)
    r = await h.execute(make_ctx(checkpoint=cp, cancel=sig))
    assert r.success is False
    assert r.output["errorCode"] == CANCELLED_CODE
    # 停在第一个 commit 安全点(写了 2 行)
    assert h.written == [{"i": 0}, {"i": 1}]
    assert cp.load(42).break_position == {"i": 1}
