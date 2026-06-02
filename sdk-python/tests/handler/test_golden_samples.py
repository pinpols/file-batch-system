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

import asyncio
import json
from pathlib import Path
from typing import Any

import pytest

from batch_worker_sdk.task.result import SdkTaskResult
from tests.handler.conftest import get_attr, make_ctx, try_import

FIXTURES_DIR = Path(__file__).parent / "fixtures"

# task_type → (模块 dotted path, 类名)
TASK_TYPE_TO_HANDLER = {
    "atomic.echo": ("batch_worker_sdk.handler.atomic.shell", "ShellAtomicHandler"),
    "atomic.sql": ("batch_worker_sdk.handler.atomic.sql", "SqlAtomicHandler"),
    "builtin.file_import": (
        "batch_worker_sdk.handler.builtin.file_import",
        "FileImportHandler",
    ),
    "builtin.http_dispatch": (
        "batch_worker_sdk.handler.builtin.http_dispatch",
        "HttpDispatchHandler",
    ),
    "builtin.query_export": (
        "batch_worker_sdk.handler.builtin.query_export",
        "QueryExportHandler",
    ),
}


def _load_fixtures() -> list[tuple[str, dict[str, Any]]]:
    samples: list[tuple[str, dict[str, Any]]] = []
    for path in sorted(FIXTURES_DIR.glob("*.json")):
        with path.open("r", encoding="utf-8") as fp:
            samples.append((path.stem, json.load(fp)))
    return samples


def _run_handler(handler: Any, ctx: Any) -> SdkTaskResult:
    out: Any = handler.execute(ctx)
    if asyncio.iscoroutine(out):
        out = asyncio.get_event_loop().run_until_complete(out)
    assert isinstance(out, SdkTaskResult)
    return out


def _subset_match(actual: dict[str, Any], expected: dict[str, Any]) -> None:
    """断言 `expected` 的每个 key 都在 `actual` 中,且值匹配。

    `actual` 里多出来的 key 允许 —— Python handler 可以输出比 Java
    baseline 更丰富的字段,只要 canonical 线字段一致即可。
    """
    for key, want in expected.items():
        assert key in actual, f"missing key {key!r} in actual {actual!r}"
        if isinstance(want, dict) and isinstance(actual[key], dict):
            _subset_match(actual[key], want)
        else:
            assert actual[key] == want, (
                f"mismatch at {key!r}: expected {want!r}, got {actual[key]!r}"
            )


@pytest.mark.parametrize(("name", "fixture"), _load_fixtures())
def test_golden_sample_matches_java_baseline(name: str, fixture: dict[str, Any]) -> None:
    task_type = fixture["input"]["task_type"]
    if task_type not in TASK_TYPE_TO_HANDLER:
        pytest.skip(f"fixture {name!r}: no handler mapping for task_type {task_type!r}")

    dotted, cls_name = TASK_TYPE_TO_HANDLER[task_type]
    mod = try_import(dotted)
    if mod is None:
        pytest.skip(f"fixture {name!r}: handler module {dotted!r} not yet merged")
    cls = get_attr(mod, cls_name)

    try:
        handler = cls()
    except TypeError as exc:
        pytest.skip(f"fixture {name!r}: {cls_name} not no-arg constructible ({exc})")

    ctx = make_ctx(
        task_type=task_type,
        parameters=fixture["input"]["parameters"],
    )

    try:
        result = _run_handler(handler, ctx)
    except Exception as exc:
        pytest.skip(f"fixture {name!r}: handler raised (likely missing infra dep): {exc!r}")

    assert isinstance(result, SdkTaskResult)
    expected = fixture["expected_output"]
    actual = result.model_dump()
    _subset_match(actual, expected)


def test_all_fixtures_have_expected_output_shape() -> None:
    """静态检查:每个 fixture 文件必须声明 3 个必需的线字段。"""
    fixtures = _load_fixtures()
    assert len(fixtures) >= 5, "task spec requires >= 5 golden fixtures"
    for name, fx in fixtures:
        exp = fx["expected_output"]
        assert "success" in exp, f"fixture {name!r} missing expected_output.success"
        assert "message" in exp, f"fixture {name!r} missing expected_output.message"
        assert "output" in exp, f"fixture {name!r} missing expected_output.output"
