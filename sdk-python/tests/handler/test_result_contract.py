"""SdkTaskResult JSON-wire serialization contract.

`SdkTaskResult` is a `pydantic` BaseModel. Its JSON shape must round-trip
through `model_dump_json` / `json.loads` and (when the structure is
ASCII-clean) through `json.dumps(model_dump())`. This guards the Java
↔ Python REPORT wire compatibility.

Three states are covered: success, failure, and exception-driven
failure.
"""

from __future__ import annotations

import json
from typing import Any

from batch_worker_sdk.task.result import SdkTaskResult


def _round_trip(result: SdkTaskResult) -> dict[str, Any]:
    """model_dump_json → loads → assert all three keys present."""
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
    """The model_dump() path (used by callers that pre-wrap an envelope)
    must produce a dict that json.dumps accepts."""
    result = SdkTaskResult.success_with(output={"count": 0}, message="done")
    blob = json.dumps(result.model_dump())
    assert json.loads(blob) == {
        "success": True,
        "output": {"count": 0},
        "message": "done",
    }


def test_empty_output_defaults_to_empty_dict() -> None:
    """Java SdkTaskResult.ok with no output → empty Map; Python mirror."""
    result = SdkTaskResult.success_with()
    parsed = _round_trip(result)
    assert parsed["output"] == {}
    assert parsed["message"] == "ok"


def test_three_state_coverage_matrix() -> None:
    """Sanity matrix: success, failure-by-fail, failure-by-exception all
    produce wire-distinguishable JSON."""
    s = SdkTaskResult.success_with(output={"a": 1})
    f = SdkTaskResult.fail("CODE_X", "msg")
    e = SdkTaskResult.fail("CODE_Y", "boom", cause=RuntimeError("rt"))
    blobs = [r.model_dump_json() for r in (s, f, e)]
    assert len(set(blobs)) == 3
    # All decode back to a dict shape.
    for blob in blobs:
        decoded = json.loads(blob)
        assert isinstance(decoded, dict)
        assert "success" in decoded
