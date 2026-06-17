"""任务执行结果(对齐 Java SdkTaskResult)。

对齐 Java ``com.example.batch.sdk.task.SdkTaskResult``。Handler 返回后
SDK 框架将其序列化为平台 REPORT 协议。Atomic Lane K 错误码
(``AtomicErrorCode``)写入 ``output``(例如
``output['errorCode'] = 'ATOMIC_TIMEOUT'``),让平台侧错误分类保持语言无关。
"""

from __future__ import annotations

from typing import Any, Self

from pydantic import BaseModel, ConfigDict, Field


class SdkTaskResult(BaseModel):
    """Handler 的返回值;SDK 将其转换为 REPORT 调用。"""

    model_config = ConfigDict(frozen=True, extra="forbid")

    success: bool
    """``True`` → orchestrator 标记 SUCCESS;``False`` → FAILED + 重试 / 补偿。"""

    output: dict[str, Any] = Field(default_factory=dict)
    """业务输出 Map —— 作为下游 ``runtimeAttributes`` 透传。"""

    message: str | None = None
    """自由文本摘要(成功)或错误说明(失败);落入审计日志。"""

    @classmethod
    def success_with(
        cls,
        output: dict[str, Any] | None = None,
        message: str | None = None,
    ) -> Self:
        """构造成功结果(对齐 Java ``SdkTaskResult.ok``)。"""
        return cls(success=True, output=output or {}, message=message or "ok")

    @classmethod
    def fail(
        cls,
        code: str,
        message: str,
        cause: Exception | None = None,
    ) -> Self:
        """构造带错误码的失败结果。

        ``code`` 放入 ``output['errorCode']``(对齐 atomic Lane K
        ``AtomicErrorCode`` 约定)。若提供 ``cause``,其类名归入
        ``output['errorClass']`` —— 完整 stacktrace 序列化等 P1+ wire 适配器
        到位再做。
        """
        output: dict[str, Any] = {"errorCode": code}
        if cause is not None:
            output["errorClass"] = type(cause).__name__
        return cls(success=False, output=output, message=message)

    @classmethod
    def cancelled(
        cls,
        break_position: dict[str, Any] | None = None,
        message: str | None = None,
    ) -> Self:
        """构造 ADR-037 协作取消的终态结果(对齐 Go/Java)。

        ``errorCode`` 固定为 ``CANCELLED``,并把取消发生时已安全提交到的断点
        ``break_position`` 放入 ``output['breakPosition']``,使平台 / 后续续跑能
        看到任务安全停在哪里。``break_position`` 为 ``None`` / 空时仍带上
        ``breakPosition``(空 dict),保证终态 output 形状跨语言一致。
        """
        output: dict[str, Any] = {
            "errorCode": "CANCELLED",
            "breakPosition": dict(break_position or {}),
        }
        return cls(
            success=False,
            output=output,
            message=message or "task cancelled at committed safe point",
        )
