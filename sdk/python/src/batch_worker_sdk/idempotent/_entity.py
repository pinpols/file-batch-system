"""幂等记录快照(对齐 Java ``SdkIdempotencyEntity``)。

命中时框架据此重建一个「成功」的 :class:`SdkTaskResult` 返回(不重跑业务)。
只存成功结果的 ``message`` + ``output``(去重语义针对成功执行;失败本就该
重试,不记录)。``output`` 存入时做防御性浅拷贝(对齐 Java ``Map.copyOf``)。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from batch_worker_sdk.task.result import SdkTaskResult


@dataclass(frozen=True)
class SdkIdempotencyEntity:
    """一次成功执行的不可变结果快照。"""

    message: str | None = None
    output: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        # 防御性拷贝 —— 调用方后续改原 dict 不污染快照(对齐 Java Map.copyOf)。
        object.__setattr__(self, "output", dict(self.output or {}))

    @classmethod
    def of_result(cls, result: SdkTaskResult) -> SdkIdempotencyEntity:
        """从一次成功结果抽快照。"""
        return cls(message=result.message, output=dict(result.output))

    def to_result(self) -> SdkTaskResult:
        """重建成功结果(命中跳过执行时返回)。"""
        return SdkTaskResult.success_with(output=dict(self.output), message=self.message)


__all__ = ["SdkIdempotencyEntity"]
