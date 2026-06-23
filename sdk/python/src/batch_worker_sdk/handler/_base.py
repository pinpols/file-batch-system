"""任务 handler 抽象基类 + 行级计数器(ADR-036)。

对齐 Java ``SdkAbstractTaskHandler`` 与 ``SdkRowResult``
(``io.github.pinpols.batch.sdk.handler``)。Java SDK 暴露同步 ``execute`` 模板
方法,锁死执行序 ``validate -> before -> doExecute -> after + finally
cleanup``,让租户代码只能填写受保护钩子。Python 版本保留同样的模板方法
形态,但将 ``execute`` 改为 ``async def`` 以契合 SDK 仅异步契约
(``pyproject.toml`` 已声明 ``Framework :: AsyncIO``)。

此基类有意结构化满足 :class:`SdkTaskHandler` ``Protocol``(``handler.py``)
—— 子类不必显式继承 ``Protocol`` 也能通过 ``isinstance(h, SdkTaskHandler)``。
"""

from __future__ import annotations

import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from typing import Any, final

from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult

logger = logging.getLogger(__name__)

# 写入 ``SdkTaskResult.output['errorCode']`` 的模板方法失败路径错误码。
# 保持短小且稳定,便于平台 atomic-lane 分类体系据此路由。
HANDLER_ERROR_CODE: str = "HANDLER_ERROR"
INVALID_PARAMS_CODE: str = "INVALID_PARAMS"
CANCELLED_CODE: str = "CANCELLED"
NULL_RESULT_CODE: str = "NULL_RESULT"


@dataclass
class SdkRowResult:
    """4 种长任务形态(Import / Export / Process / Dispatch)的行级计数器。

    对齐 Java ``SdkRowResult``:success / skipped / failed / reject 4 个计数器,
    加上 :meth:`to_output` —— 把非零项(以及 ``success`` 与 ``total``)渲染到
    :attr:`SdkTaskResult.output` map,由平台 REPORT 调用转发。

    Java 用 ``LongAdder`` 做线程安全累加;Python SDK 单任务异步单线程,
    普通 ``int`` 足矣(同一任务的 handler 不会跨线程自己跟自己竞争)。
    扇出到线程/进程池的 handler 必须自行序列化对计数器的写入。
    """

    success_count: int = field(default=0)
    skipped_count: int = field(default=0)
    failed_count: int = field(default=0)
    reject_count: int = field(default=0)

    def inc_success(self) -> None:
        self.success_count += 1

    def inc_skipped(self) -> None:
        self.skipped_count += 1

    def inc_failed(self) -> None:
        self.failed_count += 1

    def inc_reject(self) -> None:
        self.reject_count += 1

    def add_success(self, n: int) -> None:
        self.success_count += n

    def success(self) -> int:
        return self.success_count

    def skipped(self) -> int:
        return self.skipped_count

    def failed(self) -> int:
        return self.failed_count

    def reject(self) -> int:
        return self.reject_count

    def total(self) -> int:
        """已处理行总数:success + skipped + failed + reject。"""
        return self.success_count + self.skipped_count + self.failed_count + self.reject_count

    def to_output(self) -> dict[str, Any]:
        """把非零计数器渲染成 ``output`` 形 dict。

        对齐 Java ``SdkRowResult.toOutput()`` —— ``success`` 和 ``total``
        始终出现;其余只在 > 0 时出现,以便线上载荷保持紧凑。
        """
        out: dict[str, Any] = {"success": self.success()}
        if self.skipped() > 0:
            out["skipped"] = self.skipped()
        if self.failed() > 0:
            out["failed"] = self.failed()
        if self.reject() > 0:
            out["reject"] = self.reject()
        out["total"] = self.total()
        return out


class SdkAbstractTaskHandler(ABC):
    """租户 handler 的模板方法基类(ADR-036)。

    对齐 Java ``SdkAbstractTaskHandler``。子类填写受保护钩子;
    :meth:`execute` 被 :func:`typing.final` 锁死,固化执行序::

        _validate -> _before -> _do_execute -> _after
        (finally) _cleanup  -- 仅当 _before 跑过

    钩子内抛出的所有异常都会被捕获并转成 :meth:`SdkTaskResult.fail`。
    协作式取消(:attr:`SdkTaskContext.cancel_signal`)在 ``_do_execute``
    前检查一次;长任务形态在自己循环里继续轮询信号。

    结构化满足 ``handler.py`` 中声明的 :class:`SdkTaskHandler` ``Protocol``。
    """

    @abstractmethod
    def task_type(self) -> str:
        """全局唯一任务类型码(对齐 Java ``taskType()``)。"""

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        """可选 —— 自定义任务类型描述符,默认 ``None``。"""
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        """可选 —— 协作式取消钩子,默认空实现。

        与 :class:`SdkTaskHandler` Protocol 保持一致 —— 多数 handler
        改为轮询 :attr:`SdkTaskContext.cancel_signal`,不重写此方法。
        """
        return None

    @final
    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        """模板方法入口。**Final** —— 禁止重写。

        子类重写 :meth:`_do_execute`(及可选的 ``_validate`` / ``_before``
        / ``_after`` / ``_cleanup`` 钩子)。与 Java
        ``SdkAbstractTaskHandler.execute`` 1:1 对齐。
        """
        started = False
        try:
            await self._validate(ctx)
            await self._before(ctx)
            started = True
            if ctx.cancel_signal is not None and ctx.cancel_signal.is_cancellation_requested:
                # 取消发生在执行前:无已提交断点,breakPosition 为空 dict,但形状
                # 与安全点取消(_resumable._guard)统一(errorCode=CANCELLED + breakPosition)。
                return SdkTaskResult.cancelled(
                    message=f"task cancelled before execution (taskId={ctx.task_id})",
                )
            result = await self._do_execute(ctx)
            # Java 语义:handler 返回 null 转成 fail
            # (子类可能通过 type: ignore 或裸 None 绕过类型检查,在此再加一道闸)。
            if result is None:
                return SdkTaskResult.fail(  # type: ignore[unreachable]
                    NULL_RESULT_CODE,
                    "handler returned null SdkTaskResult",
                )
            await self._after(ctx, result)
            return result
        except Exception as t:
            logger.exception(
                "SDK handler %s failed (taskType=%s, taskId=%s): %s",
                type(self).__name__,
                self._safe_task_type(),
                getattr(ctx, "task_id", None),
                t,
            )
            return SdkTaskResult.fail(
                HANDLER_ERROR_CODE,
                str(t) or type(t).__name__,
                cause=t,
            )
        finally:
            if started:
                try:
                    await self._cleanup(ctx)
                except Exception as cleanup_ex:
                    logger.warning(
                        "SDK handler %s cleanup() failed: %s",
                        type(self).__name__,
                        cleanup_ex,
                    )

    # ---- 受保护钩子(子类或形态基类重写) ----

    async def _validate(self, ctx: SdkTaskContext) -> None:
        """业务入参校验。抛异常即失败。默认空实现。"""
        return None

    async def _before(self, ctx: SdkTaskContext) -> None:
        """资源获取(打开连接 / 拿租约)。默认空实现。"""
        return None

    @abstractmethod
    async def _do_execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        """真正的业务逻辑。由形态基类或最终子类实现。"""

    async def _after(self, ctx: SdkTaskContext, result: SdkTaskResult) -> None:
        """成功后钩子(异常时跳过)。默认空实现。"""
        return None

    async def _cleanup(self, ctx: SdkTaskContext) -> None:
        """``finally`` 释放钩子。默认空实现。"""
        return None

    # ---- 内部辅助 ----

    def _safe_task_type(self) -> str:
        """避免 ``task_type()`` 自身报错把原始错误掩盖掉。"""
        try:
            return self.task_type()
        except Exception:
            return "<task_type() raised>"


__all__ = [
    "CANCELLED_CODE",
    "HANDLER_ERROR_CODE",
    "INVALID_PARAMS_CODE",
    "NULL_RESULT_CODE",
    "SdkAbstractTaskHandler",
    "SdkRowResult",
]
