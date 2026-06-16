"""任务执行上下文(对齐 Java SdkTaskContext)。

对齐 Java ``com.example.batch.sdk.task.SdkTaskContext`` —— 即传给
:meth:`SdkTaskHandler.execute` 的值对象 record。Python 形态是 Java 9 参构造的
扁平化、更 Pythonic 的投影:调度上下文子 record 内联为顶层字段
(``biz_date`` / ``attempt_no`` / ``trigger_code`` / ``workflow_run_id``
/ ``is_holiday``),handler 直接读取调度事实,无需穿越嵌套对象。

字段命名遵循 PEP 8(``tenant_id`` 而非 ``tenantId``)。从派发负载物化此
对象的 wire 适配器(P1+)负责 camelCase ↔ snake_case 映射。
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field, PrivateAttr

from batch_worker_sdk.exceptions import SdkTaskStopped
from batch_worker_sdk.task.cancellation import CancellationSignal
from batch_worker_sdk.task.checkpoint import (
    InMemoryCheckpoint,
    SdkCheckpoint,
    SdkCheckpointState,
)
from batch_worker_sdk.task.progress import ProgressReporter


class SdkTaskContext(BaseModel):
    """单个任务实例的不可变执行上下文。

    对齐 Java ``SdkTaskContext``(7 参 legacy + 调度扩展)。
    ``runtime_attributes`` 是平台开放注入的逃生口,用于注入 traceId、
    pipeline-instance id、dry-run flag 之类的字段。
    """

    model_config = ConfigDict(frozen=True, extra="forbid", arbitrary_types_allowed=True)

    tenant_id: str
    """归属租户 id(必填,对齐 ``tenantId``)。"""

    task_id: int
    """Orchestrator 侧任务主键(对齐 ``taskId``)。"""

    worker_code: str
    """当前 worker 的标识(对齐 ``workerId``)。"""

    task_type: str
    """task-type 编码,用于把此次派发路由到对应 handler。"""

    parameters: dict[str, Any] = Field(default_factory=dict)
    """来自 ``job_definition.parameters`` 的用户自定义任务参数。"""

    biz_date: str | None = None
    """业务日期(ISO ``YYYY-MM-DD``);无调度上下文时为 ``None``。"""

    prev_biz_date: str | None = None
    """前一个业务日期(ISO ``YYYY-MM-DD``);调度外为 ``None``。"""

    next_biz_date: str | None = None
    """下一个业务日期(ISO ``YYYY-MM-DD``);调度外为 ``None``。"""

    is_holiday: bool | None = None
    """``biz_date`` 是否为节假日 / 周末;调度外为 ``None``。"""

    attempt_no: int = 1
    """执行尝试次数(从 1 起计;重试 / 重夺时递增)。"""

    trigger_code: str | None = None
    """来源 trigger 编码(目前恒为 ``None`` —— 字段尚未接通)。"""

    workflow_run_id: int | None = None
    """归属的 workflow run id;非 workflow 直接派发时为 ``None``。"""

    runtime_attributes: dict[str, Any] = Field(default_factory=dict)
    """平台开放注入的运行时属性(traceId / dryRun 等)。"""

    # P4 注入的运行时协作对象。不属于 wire 负载 —— 派发器在为具体任务实例
    # 物化上下文时绑定。frozen=True 仍生效(构造后不能重新赋值引用),但
    # 被引用对象本身是可变的(CancellationSignal 会翻转其内部 event,
    # ProgressReporter 在锁保护下写快照)。``exclude=True`` 让它们不出现在
    # 派发器可能打印的任何 ``model_dump()`` 输出里。
    cancel_signal: CancellationSignal | None = Field(default=None, exclude=True, repr=False)
    """本次任务执行的协作式取消信号(P4)。

    长跑 handler 应轮询
    ``ctx.cancel_signal.is_cancellation_requested``(或 ``await
    ctx.cancel_signal.wait_cancelled()``),在置位时尽早返回,不要等租约
    自然过期。仅在尚未升级的 P0.5 时期调用方处为 ``None``。
    """

    progress_reporter: ProgressReporter | None = Field(default=None, exclude=True, repr=False)
    """本次任务执行的"最新值胜出"进度槽位(P4)。

    Handler 在长循环里调用 ``ctx.progress_reporter.report({...})``;
    租约续期调度器每次 tick 采样 ``latest()`` 并塞进 renew 请求体的
    ``details`` 字段,使平台的 job-task 详情页保持新鲜。仅在尚未升级的
    P0.5 时期调用方处为 ``None``。
    """

    # ---- ADR-037 续跑原语(P1~P3)注入对象 ----
    checkpoint_store: SdkCheckpoint | None = Field(default=None, exclude=True, repr=False)
    """断点读 / 写 SPI(ADR-037 决策一)。``None`` 时 :meth:`checkpoint` 回落到
    一个进程内 :class:`InMemoryCheckpoint`(仅本地 / 测试用,不持久化)。"""

    report_interval_batches: int = Field(default=1, exclude=True, repr=False)
    """进度上报限流:每攒满这么多次 ``commit`` 才上报一次(决策二)。默认每批都报。"""

    self_report: bool = Field(default=True, exclude=True, repr=False)
    """``True`` = ``commit`` 搭车自动上报进度;business 可关掉自己控制(决策二)。"""

    # commit 计数器 —— 用于进度限流取模。私有可变状态(frozen 模型仍允许私有属性写)。
    _commit_counter: int = PrivateAttr(default=0)
    # 未注入 checkpoint_store 时惰性创建的进程内回落实现。
    _fallback_checkpoint: SdkCheckpoint | None = PrivateAttr(default=None)

    def checkpoint(self) -> SdkCheckpoint:
        """断点存储访问入口(ADR-037 决策一)。

        未注入 ``checkpoint_store`` 时返回一个**绑定到本上下文**的进程内
        :class:`InMemoryCheckpoint`(惰性创建并缓存)—— 仅本地 / 测试可用,
        生产必须注入持久化实现。
        """
        if self.checkpoint_store is not None:
            return self.checkpoint_store
        # 惰性创建并缓存到私有属性(frozen 字段不可重赋,故走 PrivateAttr)。
        if self._fallback_checkpoint is None:
            self._fallback_checkpoint = InMemoryCheckpoint()
        return self._fallback_checkpoint

    def is_cancelled(self) -> bool:
        """协作式取消是否已被请求(ADR-037 决策三)。

        读 :attr:`cancel_signal`;未注入信号时恒 ``False``。
        """
        return self.cancel_signal is not None and self.cancel_signal.is_cancellation_requested

    async def commit(
        self,
        break_position: dict[str, Any],
        *,
        succeed_count: int = 0,
        fail_count: int = 0,
        completed: bool = False,
    ) -> None:
        """三合一可靠提交一个业务批次(ADR-037 决策二 + 三)。

        一次调用原子完成:

        1. **保存断点**:写 :class:`SdkCheckpointState`(``break_position`` + 计数 +
           ``completed``)到 :meth:`checkpoint`。
        2. **限流上报进度**:``commit`` 计数每达 :attr:`report_interval_batches` 的
           整数倍且 :attr:`self_report` 为真时,经 :attr:`progress_reporter` 上报一次。
        3. **取消安全点**(决策三):本次提交落盘后若 :meth:`is_cancelled` 命中,
           抛 :class:`SdkTaskStopped`(携带 ``break_position``),让模板顶层落
           cancelled 终态 —— 取消停在批次边界,不留半批脏数据。

        **强约束**:业务数据提交与断点保存必须同事务 —— 由租户的 ``checkpoint``
        实现保证(SDK 不介入其事务边界,见 :mod:`checkpoint` 文档)。

        :raises SdkTaskStopped: 本批已安全提交后检测到取消(业务不得吞掉)。
        """
        state = SdkCheckpointState(
            break_position=break_position,
            succeed_count=succeed_count,
            fail_count=fail_count,
            completed=completed,
        )
        self.checkpoint().save(self.task_id, state)

        self._commit_counter += 1
        if (
            self.self_report
            and self.progress_reporter is not None
            and self.report_interval_batches > 0
            and self._commit_counter % self.report_interval_batches == 0
        ):
            self.progress_reporter.report(
                {
                    "succeed": succeed_count,
                    "failed": fail_count,
                    "breakPosition": dict(break_position),
                }
            )

        # 安全点:业务 + 断点已落盘,此处检查取消才不会留半批脏数据。
        if self.is_cancelled():
            raise SdkTaskStopped(break_position)

    def is_dry_run(self) -> bool:
        """本次派发是否为 dry-run 探测(ADR-026)。

        优先读 ``runtime_attributes['dryRun']``(平台注入路径),再回落到
        ``parameters['dryRun']``(handler 通过用户参数主动启用)。对齐 Java
        Lane B ``TaskContext.isDryRun()``。
        """
        for source in (self.runtime_attributes, self.parameters):
            value = source.get("dryRun")
            if isinstance(value, bool):
                return value
            if isinstance(value, str):
                return value.lower() == "true"
        return False
