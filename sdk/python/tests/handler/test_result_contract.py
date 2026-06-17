"""SdkTaskResult JSON 线协议序列化契约。

`SdkTaskResult` 是一个 `pydantic` BaseModel。其 JSON 形状必须能
通过 `model_dump_json` / `json.loads` round-trip,并且(在 ASCII
干净的情况下)能通过 `json.dumps(model_dump())` 走通。这守护
Java ↔ Python REPORT 线兼容。

覆盖三种状态:成功、失败、异常驱动的失败。
"""

from __future__ import annotations

import json
from typing import Any

from batch_worker_sdk.task.result import SdkTaskResult


def _round_trip(result: SdkTaskResult) -> dict[str, Any]:
    """model_dump_json → loads → 断言三个 key 都在。"""
    raw = result.model_dump_json()
    parsed: dict[str, Any] = json.loads(raw)
    assert parsed.keys() >= {"success", "output", "message"}
    return parsed


def test_success_result_serializes_to_wire_shape() -> None:
    result = SdkTaskResult.success_with(output={"rows": 3, "uri": "s3://x"}, message="ok")
    parsed = _round_trip(result)
    assert parsed["success"] is True
    assert parsed["output"] == {"rows": 3, "uri": "s3://x"}
    assert parsed["message"] == "ok"


def test_failure_result_carries_error_code_in_output() -> None:
    result = SdkTaskResult.fail("ATOMIC_TIMEOUT", "shell exceeded 30s")
    parsed = _round_trip(result)
    assert parsed["success"] is False
    assert parsed["output"]["errorCode"] == "ATOMIC_TIMEOUT"
    assert parsed["message"] == "shell exceeded 30s"


def test_failure_result_with_exception_carries_class_name() -> None:
    cause = ValueError("bad input")
    result = SdkTaskResult.fail("BAD_INPUT", "validation failed", cause=cause)
    parsed = _round_trip(result)
    assert parsed["success"] is False
    assert parsed["output"]["errorCode"] == "BAD_INPUT"
    assert parsed["output"]["errorClass"] == "ValueError"


def test_plain_dict_dump_is_json_serializable() -> None:
    """model_dump() 路径(调用方预包外层信封时用)必须产出一个
    json.dumps 接受的 dict。"""
    result = SdkTaskResult.success_with(output={"count": 0}, message="done")
    blob = json.dumps(result.model_dump())
    assert json.loads(blob) == {
        "success": True,
        "output": {"count": 0},
        "message": "done",
    }


def test_empty_output_defaults_to_empty_dict() -> None:
    """Java SdkTaskResult.ok 不带 output 时 → 空 Map;Python 镜像。"""
    result = SdkTaskResult.success_with()
    parsed = _round_trip(result)
    assert parsed["output"] == {}
    assert parsed["message"] == "ok"


def test_cancelled_result_carries_break_position_in_outputs() -> None:
    """#12:协作取消终态 = errorCode=CANCELLED + output['breakPosition'](对齐 Go/Java)。"""
    result = SdkTaskResult.cancelled(break_position={"id": 4096})
    parsed = _round_trip(result)
    assert parsed["success"] is False
    assert parsed["output"]["errorCode"] == "CANCELLED"
    assert parsed["output"]["breakPosition"] == {"id": 4096}


def test_cancelled_result_empty_break_position_still_present() -> None:
    """无断点(执行前取消)时,breakPosition 仍以空 dict 出现,保证形状一致。"""
    result = SdkTaskResult.cancelled()
    assert result.output["errorCode"] == "CANCELLED"
    assert result.output["breakPosition"] == {}


def test_three_state_coverage_matrix() -> None:
    """sanity 矩阵:成功、fail 失败、异常驱动失败三种状态产出可线区分
    的 JSON。"""
    s = SdkTaskResult.success_with(output={"a": 1})
    f = SdkTaskResult.fail("CODE_X", "msg")
    e = SdkTaskResult.fail("CODE_Y", "boom", cause=RuntimeError("rt"))
    blobs = [r.model_dump_json() for r in (s, f, e)]
    assert len(set(blobs)) == 3
    # 都能解码回 dict 形状。
    for blob in blobs:
        decoded = json.loads(blob)
        assert isinstance(decoded, dict)
        assert "success" in decoded
