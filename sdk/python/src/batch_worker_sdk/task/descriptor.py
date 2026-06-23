"""自定义 taskType 描述符(对齐 Java SdkTaskTypeDescriptor)。

对齐 Java ``io.github.pinpols.batch.sdk.task.SdkTaskTypeDescriptor``。由
:meth:`SdkTaskHandler.descriptor` 返回,并在 worker-register 请求体中
上送,以便 console 渲染参数表单、orchestrator 在派发时合并默认值。
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class SdkTaskTypeDescriptor(BaseModel):
    """自定义 task type 的声明式元数据。

    与 Java record 逐字段对齐,Python worker 与 Java worker 向同一 orchestrator
    注册时 wire 负载完全一致。
    """

    model_config = ConfigDict(frozen=True, extra="forbid", populate_by_name=True)

    task_type: str
    """权威 task-type 编码(与 ``SdkTaskHandler.task_type`` 保持一致)。"""

    display_name: str | None = None
    """Console 展示名(可选)。"""

    input_schema: dict[str, Any] | None = Field(default=None, alias="inputSchema")
    """``parameters`` 的 JSON Schema —— 驱动表单渲染 + 校验。

    Wire alias ``inputSchema``(对齐 Java ``inputSchema`` camelCase 字段名)。
    Python 属性名为 ``input_schema``,避免遮蔽 pydantic ``BaseModel.schema``
    类方法。
    """

    parameters: dict[str, Any] | None = None
    """默认参数值(合并到用户在节点上提供的参数之下)。"""

    outputs: dict[str, Any] | None = None
    """声明的输出形态(目前仅信息性;未来用于合约强校验)。"""

    required_env: list[str] | None = None
    """Handler 运行时需要的环境变量(例如 DB 凭据)。"""
