"""Pipeline stage 行级进度 sink(Python SDK 端)。

与 Java SDK ``PipelineStageProgressSink`` 对齐(2026-06-03 docs/design/pipeline-stage-progress-display.md)。

设计要点:
- 进程级单例 ``_state`` dict,一个 worker JVM/Python 进程同时只跑一个 CLAIM stage,无并发竞争
- handler 用 ``publish(rows, total_rows_hint=None)`` 上报,heartbeat tick 时被读取
- stage 结束调 ``clear()`` 避免心跳带上残留
- ``totalRowsHint=None`` = FE 退化为只显示计数器不显 ETA
"""

from __future__ import annotations

_state: dict[str, int | None] = {"rowsProcessed": None, "totalRowsHint": None}


def publish(rows_processed: int, total_rows_hint: int | None = None) -> None:
    """流式 handler 进度变化时调用。"""
    _state["rowsProcessed"] = rows_processed
    _state["totalRowsHint"] = total_rows_hint


def clear() -> None:
    """stage 结束时调用。"""
    _state["rowsProcessed"] = None
    _state["totalRowsHint"] = None


def current_rows_processed() -> int | None:
    """心跳读取(无值返 ``None``)。"""
    return _state["rowsProcessed"]


def current_total_rows_hint() -> int | None:
    """心跳读取(无值返 ``None``)。"""
    return _state["totalRowsHint"]
