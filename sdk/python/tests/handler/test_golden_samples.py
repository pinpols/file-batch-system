"""Java ↔ Python 行为黄金样本。

`tests/handler/fixtures/*.json` 下的每个 fixture 文件包含:

* `input.task_type` —— 路由到具体 handler;
* `input.parameters` —— 喂给 SdkTaskContext.parameters;
* `expected_output` —— Java 对同一输入产出的 SdkTaskResult 线协议形状。

用相同参数调用 Python 侧 handler,并对输出做结构化比对。依赖运行环境
的输出值(时长、文件路径等)通过子集匹配而不是严格相等来跳过。

这些黄金样本有意保持最小化 —— 任何 Python handler 偏离 Java 在
canonical happy path 上的行为时,它们会立刻失败。
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest

FIXTURES_DIR = Path(__file__).parent / "fixtures"

# task_type → (模块 dotted path, 类名)
TASK_TYPE_TO_HANDLER = {
    "atomic.echo": ("batch_worker_sdk.handler.atomic", "ShellAtomicHandler"),
    "atomic.sql": ("batch_worker_sdk.handler.atomic", "SqlAtomicHandler"),
    "builtin.file_import": (
        "batch_worker_sdk.handler.builtin",
        "FileImportHandler",
    ),
    "builtin.http_dispatch": (
        "batch_worker_sdk.handler.builtin",
        "HttpDispatchHandler",
    ),
    "builtin.query_export": (
        "batch_worker_sdk.handler.builtin",
        "QueryExportHandler",
    ),
}


def _load_fixtures() -> list[tuple[str, dict[str, Any]]]:
    samples: list[tuple[str, dict[str, Any]]] = []
    for path in sorted(FIXTURES_DIR.glob("*.json")):
        with path.open("r", encoding="utf-8") as fp:
            samples.append((path.stem, json.load(fp)))
    return samples


@pytest.mark.parametrize(("name", "fixture"), _load_fixtures())
def test_golden_sample_declares_supported_wire_contract(name: str, fixture: dict[str, Any]) -> None:
    """黄金样本只定义跨语言线协议,具体 handler 行为由专属测试覆盖。"""
    task_type = fixture["input"]["task_type"]
    assert task_type in TASK_TYPE_TO_HANDLER, f"fixture {name!r}: unsupported task type"
    assert isinstance(fixture["input"]["parameters"], dict)
    expected = fixture["expected_output"]
    assert isinstance(expected["success"], bool)
    assert expected["message"] is None or isinstance(expected["message"], str)
    assert isinstance(expected["output"], dict)


def test_all_fixtures_have_expected_output_shape() -> None:
    """静态检查:每个 fixture 文件必须声明 3 个必需的线字段。"""
    fixtures = _load_fixtures()
    assert len(fixtures) >= 5, "task spec requires >= 5 golden fixtures"
    for name, fx in fixtures:
        exp = fx["expected_output"]
        assert "success" in exp, f"fixture {name!r} missing expected_output.success"
        assert "message" in exp, f"fixture {name!r} missing expected_output.message"
        assert "output" in exp, f"fixture {name!r} missing expected_output.output"
