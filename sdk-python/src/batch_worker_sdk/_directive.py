"""Platform directive parsing for heartbeat responses.

Heartbeats POST ``/internal/workers/{code}/heartbeat`` and the response
body carries an orch-side **directive** telling the worker what to do
next:

- ``runtimeState`` — drives the 4-state FSM
  (``NORMAL`` / ``DEGRADED`` / ``PAUSED`` / ``DRAINING``).
- ``shouldDrain`` — explicit drain flag (set alongside DRAINING, but
  some flows set it standalone for soft-cancel).
- ``desiredMaxConcurrent`` — orch suggests lowering local concurrency.
- ``pausedTaskTypes`` — orch wants these task types skipped on claim.
- ``nextHeartbeatHint`` — Lane I (PR #251): dynamic heartbeat re-pacing.
  Accepted forms (Java equivalence):

  1. **integer / float seconds** — raw number → ``timedelta(seconds=N)``.
     This matches Java's ``Long`` field shape (the Java side multiplies
     by 1000 to get ms).
  2. **ISO 8601 duration string** — ``"PT5S"`` → ``timedelta(seconds=5)``.
     Tolerated for forward-compat with future orch versions that emit
     ``java.time.Duration`` directly.

The parsing here is intentionally **defensive**: any unknown / malformed
field is logged at WARN and dropped, leaving the corresponding
``ParsedDirective`` slot at its safe default. Heartbeat path must never
crash the scheduler — Java equivalent ``HeartbeatScheduler.tick`` wraps
the whole body in a ``Throwable`` catch for the same reason.

Lane T scope: **parsing only**. The dispatcher-side application of the
parsed directive (FSM transition + drain + paused-types enforcement) is
owned by Lane S (P2 ``TaskDispatcher.apply_platform_directive``); this
module just hands it a dataclass so it never has to re-parse.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import timedelta
from typing import Any

from batch_worker_sdk.state import WorkerRuntimeState

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class ParsedDirective:
    """Typed view of the heartbeat-response ``directive`` block.

    All fields default to the "no change" / safe value so a missing /
    malformed field never triggers a state transition.
    """

    platform_status: WorkerRuntimeState = WorkerRuntimeState.NORMAL
    """Target runtime state. Defaults ``NORMAL`` if absent (back-compat)."""

    should_drain: bool = False
    """Soft-drain hint. ``True`` even outside ``DRAINING`` triggers stop-claiming."""

    desired_max_concurrent: int | None = None
    """Orch-suggested concurrency cap, or ``None`` for "keep local config"."""

    paused_task_types: list[str] = field(default_factory=list)
    """Task types orch wants skipped on the next claim window. May be empty."""

    next_heartbeat_hint: timedelta | None = None
    """Dynamic re-pacing hint, or ``None`` to keep the current cadence."""

    raw: dict[str, Any] = field(default_factory=dict)
    """Original payload, retained for diagnostics / observability sinks."""


def parse_directive(raw: dict[str, Any] | None) -> ParsedDirective:
    """Parse a heartbeat response body into :class:`ParsedDirective`.

    Tolerates:

    - ``None`` / missing body → all-default directive.
    - Unknown ``runtimeState`` enum values → defaults to ``NORMAL`` with WARN log.
    - Malformed numbers / hints → field stays default; never raises.

    Args:
        raw: Raw heartbeat response body as decoded by ``PlatformHttpClient``.
            Typically contains a top-level ``directive`` key, but for
            back-compat we also accept the directive fields at the root.

    Returns:
        Immutable :class:`ParsedDirective` ready to feed into
        ``TaskDispatcher.apply_platform_directive``.
    """
    if not raw:
        return ParsedDirective(raw={})
    # Heartbeat envelopes either nest the directive under "directive"
    # (current orch) or inline (older orch versions / fixtures).
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
    """Parse ``nextHeartbeatHint`` accepting seconds-number or ISO 8601.

    Java ships a ``Long`` seconds value; the Python orch tolerates both
    that and ``java.time.Duration``'s ``toString()`` (``"PT5S"``) so
    fixtures hand-written by humans don't need to know which side wrote
    them.
    """
    if raw is None:
        return None
    if isinstance(raw, bool):
        # bool is an int subclass in Python; treat as malformed
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
        # ISO 8601 — only support the "PT<n>S" / "PT<n>M" subset used by
        # java.time.Duration. Avoid pulling in a date-parsing dep for one
        # field; if orch starts emitting more exotic durations we'll
        # revisit.
        if s.startswith("PT") and (s.endswith("S") or s.endswith("M") or s.endswith("H")):
            return _parse_iso8601_duration(s)
        # Plain "5" / "5.5" strings — accept as seconds.
        try:
            return timedelta(seconds=float(s))
        except ValueError:
            logger.warning("malformed nextHeartbeatHint string: %r (ignored)", raw)
            return None
    logger.warning("nextHeartbeatHint unsupported type %s (ignored)", type(raw).__name__)
    return None


def _parse_iso8601_duration(s: str) -> timedelta | None:
    """Minimal ISO 8601 ``PT<num><unit>`` parser (seconds / minutes / hours)."""
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
