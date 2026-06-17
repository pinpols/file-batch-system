"""续跑 / 可靠提交 / 协作取消 的 typed 模板混入(ADR-037 决策一~三,P1~P3)。

被 :class:`SdkAbstractTypedImportHandler` / ``...Process`` / ``...Export`` 复用,
把三件横切能力织进模板执行序:

1. **续跑**(决策一):``_resume`` 在 ``_do_execute`` 开头读断点 —— 已 ``completed``
   则直接跳过(幂等),否则把 ``succeed/fail`` 计数恢复进 :class:`SdkRowResult`、
   把 ``break_position`` 透传给租户的 ``read_rows`` / ``select_input`` 做续读起点。
2. **可靠提交**(决策二):每个 flush 批次走 ``await ctx.commit(break_key)``,
   原子完成业务提交(租户 checkpoint 实现保证同事务)+ 断点保存 + 限流上报。
3. **协作取消**(决策三):``commit`` 在安全点抛 :class:`SdkTaskStopped`,模板顶层
   ``_guard`` 捕获 → 返回 cancelled 终态(``output['errorCode'] = CANCELLED``)。

租户**可选**重写 ``checkpoint_break_key`` 提供续读坐标;不重写时 ``commit`` 仍
履行「进度上报 + 取消安全点 + 计数落盘」,只是 ``break_position`` 为空(无法续读,
但崩溃后不会重复已提交批次以外的进度)。
"""

from __future__ import annotations

from collections.abc import Awaitable
from typing import Any

from batch_worker_sdk.exceptions import SdkTaskStopped
from batch_worker_sdk.handler._base import SdkRowResult
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.result import SdkTaskResult


class _ResumeOutcome:
    """``_resume`` 的返回:要么续跑(给定起点 + 已恢复计数),要么已完成跳过。"""

    __slots__ = ("already_completed", "break_position", "counts")

    def __init__(
        self,
        *,
        already_completed: bool,
        break_position: dict[str, Any],
        counts: SdkRowResult,
    ) -> None:
        self.already_completed = already_completed
        self.break_position = break_position
        self.counts = counts


class ResumableTemplateMixin:
    """断点续跑 / 可靠提交 / 协作取消 的复用混入。"""

    def resume_from(self, ctx: SdkTaskContext) -> dict[str, Any]:
        """租户在 ``read_rows`` / ``select_input`` 里读「续读起点」的入口。

        返回上次断点的 ``break_position``(首次运行 / 已完成 → 空 dict)。租户据此
        给查询加 ``WHERE id > :last`` 之类续读条件;不调则从头读(靠业务自身幂等兜底)。
        """
        state = ctx.checkpoint().load(ctx.task_id)
        if state is None or state.completed:
            return {}
        return dict(state.break_position)

    def checkpoint_break_key(
        self,
        ctx: SdkTaskContext,
        last_row: Any,
    ) -> dict[str, Any]:
        """从一个 flush 批次的**最后一行**导出续跑断点键(默认空 dict)。

        续读需要时**必须**重写:返回如 ``{"id": last_row["id"]}`` —— 必须与
        ``read_rows`` / ``select_input`` 用来续读的坐标系一致(主键 / 排序键 / 行号,
        非 offset)。返回空 dict 表示不提供续读起点(commit 仍履行其余职责)。
        """
        return {}

    def _resume(self, ctx: SdkTaskContext) -> _ResumeOutcome:
        """读断点;恢复计数;返回续跑起点或「已完成」标记。"""
        state = ctx.checkpoint().load(ctx.task_id)
        counts = SdkRowResult()
        if state is None:
            return _ResumeOutcome(already_completed=False, break_position={}, counts=counts)
        if state.completed:
            counts.add_success(state.succeed_count)
            counts.failed_count = state.fail_count
            return _ResumeOutcome(already_completed=True, break_position={}, counts=counts)
        # 续跑:恢复计数,不归零(对齐 ADR-037 决策一)。
        counts.add_success(state.succeed_count)
        counts.failed_count = state.fail_count
        return _ResumeOutcome(
            already_completed=False,
            break_position=dict(state.break_position),
            counts=counts,
        )

    async def _commit_batch(
        self,
        ctx: SdkTaskContext,
        last_row: Any,
        counts: SdkRowResult,
        *,
        completed: bool = False,
    ) -> None:
        """对一个已写出的批次做三合一 commit(可能抛 :class:`SdkTaskStopped`)。"""
        break_key = self.checkpoint_break_key(ctx, last_row)
        await ctx.commit(
            break_key,
            succeed_count=counts.success(),
            fail_count=counts.failed(),
            completed=completed,
        )

    @staticmethod
    async def _guard(coro: Awaitable[SdkTaskResult]) -> SdkTaskResult:
        """顶层捕获 :class:`SdkTaskStopped` → cancelled 终态(决策三)。

        业务不得吞掉 ``SdkTaskStopped``;到这一层统一转成 REPORT 可识别的
        cancelled 结果。
        """
        try:
            return await coro
        except SdkTaskStopped as stop:
            # 统一终态形状(对齐 Go/Java):errorCode=CANCELLED + outputs.breakPosition
            # 携带已安全提交到的断点,供平台 / 后续续跑读取停点。
            return SdkTaskResult.cancelled(
                break_position=stop.break_position,
                message=f"task cancelled at safe-point break_position={stop.break_position}",
            )


__all__ = ["ResumableTemplateMixin"]
