"""Lane A #1 — :class:`SdkTaskTypeDescriptor` wire alias 守护。

``input_schema`` 字段的 wire alias 必须是 camelCase ``inputSchema``,对齐
Java ``SdkTaskTypeDescriptor.inputSchema`` 与 wire-protocol(`worker-register`
请求体);若退回小写 ``schema`` 则 console 渲染 + orchestrator 默认值合并
都会因字段缺失静默退化为 null。
"""

from __future__ import annotations

import json

from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor


def test_input_schema_serializes_as_camelcase_input_schema() -> None:
    descriptor = SdkTaskTypeDescriptor(
        task_type="demo.echo",
        input_schema={"type": "object", "properties": {"name": {"type": "string"}}},
    )
    payload = json.loads(descriptor.model_dump_json(by_alias=True))

    assert "inputSchema" in payload, payload
    assert "schema" not in payload, payload
    assert payload["inputSchema"]["properties"]["name"]["type"] == "string"


def test_input_schema_accepts_camelcase_alias_on_validate() -> None:
    # 反序列化时也得认 wire alias ``inputSchema``,否则同步 Java 给的
    # JSON 回不来。其他字段沿用 snake_case Python 属性名(模型当前仅
    # ``input_schema`` 有显式 alias)。
    wire = {
        "task_type": "demo.echo",
        "inputSchema": {"type": "object"},
    }
    descriptor = SdkTaskTypeDescriptor.model_validate(wire)
    assert descriptor.input_schema == {"type": "object"}


def test_python_attribute_name_preserved() -> None:
    # Pydantic ``populate_by_name=True``:Python 代码用属性名 ``input_schema``
    # 构造也必须成立,避免内部代码被迫拼 camelCase。
    descriptor = SdkTaskTypeDescriptor(task_type="demo.echo", input_schema={"x": 1})
    assert descriptor.input_schema == {"x": 1}
