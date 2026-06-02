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

from pydantic import BaseModel, ConfigDict, Field

from batch_worker_sdk.task.cancellation import CancellationSignal
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
