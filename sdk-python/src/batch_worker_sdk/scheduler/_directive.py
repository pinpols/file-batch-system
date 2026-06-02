"""心跳响应中平台 directive 的解析逻辑。

心跳 POST ``/internal/workers/{code}/heartbeat`` 后,响应体携带一段 orch 端
下发的 **directive**,告诉 worker 下一步要做什么:

- ``runtimeState`` —— 驱动四态 FSM
  (``NORMAL`` / ``DEGRADED`` / ``PAUSED`` / ``DRAINING``)。
- ``shouldDrain`` —— 显式 drain 标志(通常与 DRAINING 同时下发,也有少数
  软取消流单独使用)。
- ``desiredMaxConcurrent`` —— orch 建议降低的本地并发上限。
- ``pausedTaskTypes`` —— orch 希望在下次 claim 窗口跳过的任务类型。
- ``nextHeartbeatHint`` —— PR #251:动态心跳节流提示。接受两种形式
  (与 Java 端等价):

  1. **整数 / 浮点秒数** —— 原始数字 → ``timedelta(seconds=N)``。对应
     Java 端 ``Long`` 字段(Java 端再乘 1000 得到毫秒)。
  2. **ISO 8601 duration 字符串** —— ``"PT5S"`` → ``timedelta(seconds=5)``。
     为兼容将来直接下发 ``java.time.Duration`` 的 orch 版本而预留。

此处解析刻意 **防御式**:任何未知 / 格式错误的字段都 WARN 后丢弃,对应
``ParsedDirective`` 槽位保持安全默认值。心跳链路绝不能崩掉 scheduler ——
Java 端 ``HeartbeatScheduler.tick`` 也是用 ``Throwable`` catch 包整体逻辑,
同一理由。

本模块仅负责 **解析**;dispatcher 端对 directive 的应用(FSM 切换 + drain +
paused-types 拦截)由 :meth:`TaskDispatcher.apply_platform_directive` 负责。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

from batch_worker_sdk.task.state import WorkerRuntimeState

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class ParsedDirective:
    """对心跳响应里 ``directive`` 块的强类型视图。

    所有字段默认值均为"不变化 / 安全值",这样缺失或格式错误的字段不会
    触发本地状态切换。
    """

    platform_status: WorkerRuntimeState = WorkerRuntimeState.NORMAL
    """目标运行态;缺失时默认为 ``NORMAL``(向后兼容)。"""

    should_drain: bool = False
    """软 drain 提示。即便不在 ``DRAINING`` 状态,``True`` 也会触发停止 claim。"""

    desired_max_concurrent: int | None = None
    """orch 建议的并发上限;``None`` 表示"保持本地配置"。"""

    paused_task_types: list[str] = field(default_factory=list)
    """orch 想在下次 claim 窗口跳过的任务类型,可能为空列表。"""

    next_heartbeat_hint: timedelta | None = None
    """动态节流提示;``None`` 表示沿用当前心跳频率。"""

    raw: dict[str, Any] = field(default_factory=dict)
    """原始 payload,留作诊断 / 可观测性 sink 使用。"""


def parse_directive(raw: dict[str, Any] | None) -> ParsedDirective:
    """将心跳响应体解析为 :class:`ParsedDirective`。

    容错策略:

    - ``None`` / 空 body → 返回全默认值 directive。
    - 未知 ``runtimeState`` 枚举值 → 回落为 ``NORMAL`` 并 WARN 记录。
    - 数字 / hint 格式异常 → 该字段保持默认值,永不抛错。

    Args:
        raw: ``PlatformHttpClient`` 解码后的心跳响应体。常见情况是顶层带
            ``directive`` 键;为兼容旧 orch / fixture,也接受直接放在根部
            的 directive 字段。

    Returns:
        不可变的 :class:`ParsedDirective`,可直接交给
        ``TaskDispatcher.apply_platform_directive``。
    """
    if not raw:
        return ParsedDirective(raw={})
    # 心跳信封要么把 directive 嵌套在 "directive" 键下(当前 orch),要么
    # 直接放在根部(较老的 orch 版本 / fixture)。
    nested = raw.get("directive")
    payload: dict[str, Any] = nested if isinstance(nested, dict) else raw

    state = _parse_state(payload.get("runtimeState") or payload.get("platformStatus"))
    drain = bool(payload.get("shouldDrain", False))
    desired = _parse_int(payload.get("desiredMaxConcurrent"))
    paused = _parse_str_list(payload.get("pausedTaskTypes"))
    hint = _parse_hint(payload.get("nextHeartbeatHint"))

    return ParsedDirective(
        platform_status=state,
        should_drain=drain,
        desired_max_concurrent=desired,
        paused_task_types=paused,
        next_heartbeat_hint=hint,
        raw=dict(payload),
    )


def _parse_state(raw: Any) -> WorkerRuntimeState:
    if raw is None:
        return WorkerRuntimeState.NORMAL
    try:
        return WorkerRuntimeState(str(raw))
    except ValueError:
        logger.warning("unknown runtimeState in directive: %r (defaulting NORMAL)", raw)
        return WorkerRuntimeState.NORMAL


def _parse_int(raw: Any) -> int | None:
    if raw is None:
        return None
    try:
        n = int(raw)
    except (TypeError, ValueError):
        logger.warning("malformed desiredMaxConcurrent: %r (ignored)", raw)
        return None
    return n if n >= 0 else None


def _parse_str_list(raw: Any) -> list[str]:
    if raw is None:
        return []
    if not isinstance(raw, list):
        logger.warning("pausedTaskTypes is not a list: %r (ignored)", raw)
        return []
    return [str(x) for x in raw if x is not None]


def _parse_hint(raw: Any) -> timedelta | None:  # noqa: PLR0911
    """解析 ``nextHeartbeatHint``,接受秒数或 ISO 8601 duration。

    Java 端下发的是 ``Long`` 类型的秒数;为了兼容写 fixture 的人不必关心
    是哪一侧产出的,Python 端同时接受 ``java.time.Duration`` 的
    ``toString()`` 形式(如 ``"PT5S"``)。
    """
    if raw is None:
        return None
    if isinstance(raw, bool):
        # Python 里 bool 是 int 的子类,这里视作格式错误
        logger.warning("nextHeartbeatHint must not be a bool: %r (ignored)", raw)
        return None
    if isinstance(raw, int | float):
        try:
            return timedelta(seconds=float(raw))
        except (TypeError, ValueError, OverflowError):
            logger.warning("malformed nextHeartbeatHint numeric: %r (ignored)", raw)
            return None
    if isinstance(raw, str):
        s = raw.strip()
        if not s:
            return None
        # ISO 8601:仅支持 java.time.Duration 用到的 "PT<n>S" / "PT<n>M" /
        # "PT<n>H" 子集。不引入日期解析库;若 orch 将来下发更复杂的形式
        # 再扩展。
        if s.startswith("PT") and (s.endswith("S") or s.endswith("M") or s.endswith("H")):
            return _parse_iso8601_duration(s)
        # 纯数字字符串 "5" / "5.5" —— 按秒数处理。
        try:
            return timedelta(seconds=float(s))
        except ValueError:
            logger.warning("malformed nextHeartbeatHint string: %r (ignored)", raw)
            return None
    logger.warning("nextHeartbeatHint unsupported type %s (ignored)", type(raw).__name__)
    return None


def _parse_iso8601_duration(s: str) -> timedelta | None:
    """极简 ISO 8601 ``PT<num><unit>`` 解析器(支持秒 / 分 / 时)。"""
    body = s[2:]  # strip "PT"
    unit = body[-1]
    num_str = body[:-1]
    try:
        num = float(num_str)
    except ValueError:
        logger.warning("malformed ISO 8601 duration: %r (ignored)", s)
        return None
    if unit == "S":
        return timedelta(seconds=num)
    if unit == "M":
        return timedelta(minutes=num)
    if unit == "H":
        return timedelta(hours=num)
    logger.warning("unsupported ISO 8601 duration unit in %r (ignored)", s)
    return None
