"""幂等去重(Task A)单测 —— 对齐 Java ``SdkIdempotentHandlerTest``。"""

from __future__ import annotations

import asyncio

import pytest

from batch_worker_sdk.idempotent import (
    IDEMPOTENT_IN_FLIGHT_CODE,
    IDEMPOTENT_KEY_ERROR_CODE,
    InMemoryIdempotencyStore,
    NoOpIdempotencyStore,
    SdkIdempotencyEntity,
    SdkIdempotentHandler,
    idempotent,
    resolve_key,
    wrap_idempotent,
)
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult


def make_ctx(parameters: dict | None = None) -> SdkTaskContext:
    return SdkTaskContext(
        tenant_id="t1",
        task_id=42,
        worker_code="w-1",
        task_type="tenant_import",
        parameters=parameters or {},
    )


@idempotent(key="import:{tenant_id}:{order_id}")
class _AnnotatedHandler:
    def __init__(self) -> None:
        self.executions = 0
        self.fail_business = False

    def task_type(self) -> str:
        return "tenant_import"

    def descriptor(self):
        return None

    def cancel(self, ctx) -> None:
        return None

    async def execute(self, ctx) -> SdkTaskResult:
        self.executions += 1
        if self.fail_business:
            return SdkTaskResult.fail("BUSINESS", "boom")
        return SdkTaskResult.success_with(output={"rows": 10}, message="imported")


class _PlainHandler:
    def task_type(self) -> str:
        return "plain"

    def descriptor(self):
        return None

    def cancel(self, ctx) -> None:
        return None

    async def execute(self, ctx) -> SdkTaskResult:
        return SdkTaskResult.success_with(message="done")


# ---- key resolver --------------------------------------------------------


def test_resolve_key_context_and_params() -> None:
    ctx = make_ctx({"order_id": "A1"})
    assert resolve_key("import:{tenant_id}:{order_id}", ctx) == "import:t1:A1"
    # camelCase 别名也行
    assert resolve_key("k:{tenantId}:{taskId}", ctx) == "k:t1:42"


def test_resolve_key_missing_placeholder_raises() -> None:
    with pytest.raises(ValueError, match="resolved to null"):
        resolve_key("import:{tenant_id}:{order_id}", make_ctx())


# ---- store: atomic try_acquire ------------------------------------------


def test_in_memory_try_acquire_is_atomic_dedup() -> None:
    store = InMemoryIdempotencyStore()
    assert store.try_acquire("k") is True
    # 第二次抢同 key 立刻 False(无 TOCTOU 窗口)
    assert store.try_acquire("k") is False
    assert store.find("k") is None  # 占位但未回填
    store.record("k", SdkIdempotencyEntity(message="ok", output={"n": 1}))
    found = store.find("k")
    assert found is not None
    assert found.output == {"n": 1}
    # 回填后仍不可再抢
    assert store.try_acquire("k") is False


def test_noop_store_never_dedups() -> None:
    store = NoOpIdempotencyStore()
    assert store.try_acquire("k") is True
    assert store.try_acquire("k") is True
    assert store.find("k") is None


# ---- decorator wiring ----------------------------------------------------


def test_wrap_plain_handler_returns_original() -> None:
    h = _PlainHandler()
    assert wrap_idempotent(h, InMemoryIdempotencyStore()) is h


def test_wrap_annotated_without_store_fails_fast() -> None:
    with pytest.raises(RuntimeError, match="未注入"):
        wrap_idempotent(_AnnotatedHandler(), None)


@pytest.mark.asyncio
async def test_first_execute_runs_and_records() -> None:
    store = InMemoryIdempotencyStore()
    h = _AnnotatedHandler()
    wrapped = wrap_idempotent(h, store)
    r = await wrapped.execute(make_ctx({"order_id": "A1"}))
    assert r.success is True
    assert h.executions == 1
    assert store.find("import:t1:A1") is not None


@pytest.mark.asyncio
async def test_redelivery_hits_cache_and_skips_execution() -> None:
    store = InMemoryIdempotencyStore()
    h = _AnnotatedHandler()
    wrapped = wrap_idempotent(h, store)
    first = await wrapped.execute(make_ctx({"order_id": "A1"}))
    second = await wrapped.execute(make_ctx({"order_id": "A1"}))
    # 业务只跑了一次
    assert h.executions == 1
    assert second.success is True
    assert second.output == first.output == {"rows": 10}
    assert second.message == "imported"


@pytest.mark.asyncio
async def test_failed_business_is_not_recorded() -> None:
    store = InMemoryIdempotencyStore()
    h = _AnnotatedHandler()
    h.fail_business = True
    wrapped = wrap_idempotent(h, store)
    r = await wrapped.execute(make_ctx({"order_id": "A1"}))
    assert r.success is False
    # 失败不落库 —— 留给平台重试
    assert store.find("import:t1:A1") is None
    # 占位也已释放,可重抢
    assert store.try_acquire("import:t1:A1") is True


@pytest.mark.asyncio
async def test_key_resolution_failure_returns_fail() -> None:
    store = InMemoryIdempotencyStore()
    wrapped = wrap_idempotent(_AnnotatedHandler(), store)
    r = await wrapped.execute(make_ctx())  # 缺 order_id
    assert r.success is False
    assert r.output["errorCode"] == IDEMPOTENT_KEY_ERROR_CODE


@pytest.mark.asyncio
async def test_in_flight_duplicate_returns_retryable_fail() -> None:
    store = InMemoryIdempotencyStore()
    # 模拟另一副本已抢到执行权但还没回填
    assert store.try_acquire("import:t1:A1") is True
    wrapped = wrap_idempotent(_AnnotatedHandler(), store)
    r = await wrapped.execute(make_ctx({"order_id": "A1"}))
    assert r.success is False
    assert r.output["errorCode"] == IDEMPOTENT_IN_FLIGHT_CODE


@pytest.mark.asyncio
async def test_concurrent_redeliveries_execute_once() -> None:
    """并发两份重派:try_acquire 原子,只有一份真正执行。"""
    store = InMemoryIdempotencyStore()
    h = _AnnotatedHandler()
    wrapped = wrap_idempotent(h, store)
    results = await asyncio.gather(
        wrapped.execute(make_ctx({"order_id": "A1"})),
        wrapped.execute(make_ctx({"order_id": "A1"})),
    )
    # 业务最多跑一次(另一份要么命中缓存,要么 in-flight 失败)
    assert h.executions == 1
    successes = [r for r in results if r.success]
    assert len(successes) >= 1


def test_idempotent_passthrough_methods() -> None:
    store = InMemoryIdempotencyStore()
    wrapped = wrap_idempotent(_AnnotatedHandler(), store)
    assert isinstance(wrapped, SdkIdempotentHandler)
    assert wrapped.task_type() == "tenant_import"
    assert wrapped.descriptor() is None
    wrapped.cancel(make_ctx())  # 不报错


def test_empty_key_rejected() -> None:
    with pytest.raises(ValueError, match="non-empty"):
        idempotent(key="")
