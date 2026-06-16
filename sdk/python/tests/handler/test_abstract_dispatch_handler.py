"""SdkAbstractDispatchHandler 形状的单元测试。"""

from __future__ import annotations

from collections.abc import AsyncIterator

from batch_worker_sdk import SdkAbstractDispatchHandler, SdkTaskContext
from batch_worker_sdk.testkit import make_test_context


class _RecordingDispatch(SdkAbstractDispatchHandler[str]):
    def __init__(self, targets: list[str], fail_for: set[str] | None = None) -> None:
        self.targets = targets
        self.fail_for = fail_for or set()
        self.pushed: list[str] = []

    def task_type(self) -> str:
        return "dispatch-rec"

    async def _resolve_targets(self, ctx: SdkTaskContext) -> AsyncIterator[str]:  # type: ignore[override]
        for t in self.targets:
            yield t

    async def _dispatch_to_target(self, ctx: SdkTaskContext, target: str) -> None:
        if target in self.fail_for:
            raise RuntimeError(f"push failed for {target}")
        self.pushed.append(target)


async def test_dispatch_all_success() -> None:
    h = _RecordingDispatch(targets=["a", "b", "c"])
    r = await h.execute(make_test_context())
    assert r.success is True
    assert h.pushed == ["a", "b", "c"]
    assert r.output["success"] == 3
    assert r.output["total"] == 3


async def test_dispatch_per_item_failure_does_not_abort() -> None:
    h = _RecordingDispatch(targets=["a", "b", "c"], fail_for={"b"})
    r = await h.execute(make_test_context())
    assert r.success is True  # 即使有单项失败,批级别仍算成功
    assert h.pushed == ["a", "c"]
    assert r.output["success"] == 2
    assert r.output["failed"] == 1
    assert r.output["total"] == 3
    assert "dispatched 2/3" in (r.message or "")
