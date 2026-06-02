"""Phased shutdown for :class:`BatchPlatformClient` (P4).

Mirrors Java SDK Lane A ``BatchPlatformClient.stop(Duration timeout)``
(see ``batch-worker-sdk/.../client/BatchPlatformClient.java`` #239) —
a budget-split shutdown that gives each phase a slice of the total
timeout, logs WARN with the in-flight task ids when drain overruns,
and never lets one phase failure block the next.

Phase budget (matches Java Lane A and Lane S contract fixture
``12-stop-with-timeout``):

================ ===== ==================================================
Phase             Pct   Action
================ ===== ==================================================
draining flag     0%    Flip ``dispatcher.draining = True`` synchronously
Kafka consumer   20%    ``await client.consumer.stop()``
in-flight drain  60%    Poll ``dispatcher.in_flight_count()`` until zero
scheduler stop   20%    Stop heartbeat / lease-renew schedulers
================ ===== ==================================================

Each phase computes its deadline from the *remaining* total budget so
an early-finishing phase yields its slack to the next one rather than
inflating total wall-clock.

The function does NOT import :class:`BatchPlatformClient` (lives in
Lane T's ``client.py``) — type hints use a string forward reference to
avoid the circular import. Duck-typed accessors expected on the
``client`` argument:

- ``client.consumer`` — Kafka consumer with ``async stop()``,
  ``async start_draining()`` optional
- ``client.dispatcher`` — has ``in_flight_count() -> int``,
  ``in_flight_task_ids() -> list[int]``, ``start_draining()`` and
  optionally ``async stop()``
- ``client.schedulers`` — iterable of objects each with ``async stop()``
  (heartbeat scheduler, lease-renewal scheduler)
- ``client.deactivate()`` — optional; the HTTP /deactivate call.
  Lane T's BatchPlatformClient owns this; we call it best-effort and
  swallow errors (orchestrator heartbeat-timeout reclaims after 120s
  anyway, per fixture 12).
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from typing import Any

logger = logging.getLogger(__name__)

# Phase budget fractions (must sum to <= 1.0; 0% slack is acceptable).
_KAFKA_FRACTION = 0.20
_DRAIN_FRACTION = 0.60
_SCHEDULER_FRACTION = 0.20

# Poll interval for the in-flight drain loop. 100ms matches the Java
# Lane A spin loop; small enough to react quickly, large enough to not
# spam logs.
_DRAIN_POLL_INTERVAL = 0.1


def _remaining(deadline: float) -> float:
    """Return seconds until ``deadline``, never negative."""
    loop = asyncio.get_event_loop()
    return max(0.0, deadline - loop.time())


async def _phase_stop_kafka(client: Any, budget: float) -> None:
    """Phase 2: stop the Kafka consumer within ``budget`` seconds.

    Looks up the Kafka consumer under the attribute names used by Lane T's
    ``BatchPlatformClient`` (``_kafka``) and Lane S's provisional
    ``run_worker`` (``consumer``). First non-None wins.
    """
    consumer = getattr(client, "_kafka", None) or getattr(client, "consumer", None)
    if consumer is None:
        return
    stop = getattr(consumer, "stop", None)
    if stop is None:
        return
    try:
        await asyncio.wait_for(stop(), timeout=max(budget, 0.001))
    except TimeoutError:
        logger.warning(
            "stop_with_timeout: Kafka consumer stop overran %.3fs budget; continuing",
            budget,
        )
    except Exception as exc:
        logger.warning("stop_with_timeout: Kafka consumer stop failed: %s; continuing", exc)


async def _phase_drain_in_flight(client: Any, deadline: float) -> None:
    """Phase 3: drain in-flight tasks until zero or deadline.

    Prefers ``dispatcher.shutdown(timeout)`` when available (the dispatcher's
    own drain implementation, mirroring Java ``TaskDispatcher.shutdown``).
    Falls back to polling ``in_flight_count()`` for dispatchers that don't
    expose a shutdown method.
    """
    dispatcher = getattr(client, "dispatcher", None)
    if dispatcher is None:
        return
    shutdown = getattr(dispatcher, "shutdown", None)
    if shutdown is not None:
        budget = max(0.0, _remaining(deadline))
        try:
            await asyncio.wait_for(shutdown(budget), timeout=max(budget, 0.001))
        except TimeoutError:
            ids_fn = getattr(dispatcher, "in_flight_task_ids", None)
            try:
                ids = list(ids_fn()) if ids_fn is not None else []
            except Exception:
                ids = []
            logger.warning(
                "stop_with_timeout: dispatcher.shutdown overran %.3fs; leftover ids=%s",
                budget,
                ids,
            )
        except Exception as exc:
            logger.warning("stop_with_timeout: dispatcher.shutdown raised %s; continuing", exc)
        return
    in_flight_count = getattr(dispatcher, "in_flight_count", None)
    if in_flight_count is None:
        return

    while True:
        try:
            n = int(in_flight_count())
        except Exception as exc:
            logger.warning("stop_with_timeout: in_flight_count() raised %s; aborting drain", exc)
            return
        if n <= 0:
            return
        if _remaining(deadline) <= 0:
            # Timed out — log the leftover ids so operators can correlate.
            ids_fn = getattr(dispatcher, "in_flight_task_ids", None)
            try:
                ids = list(ids_fn()) if ids_fn is not None else []
            except Exception as exc:
                logger.warning(
                    "stop_with_timeout: in_flight_task_ids() raised %s; ids unavailable",
                    exc,
                )
                ids = []
            logger.warning(
                "stop_with_timeout: drain timed out with %d in-flight task(s): %s",
                n,
                ids,
            )
            return
        # Sleep the smaller of the poll interval or remaining budget so
        # we don't oversleep past the deadline.
        await asyncio.sleep(min(_DRAIN_POLL_INTERVAL, _remaining(deadline)))


async def _phase_stop_schedulers(client: Any, budget: float) -> None:
    """Phase 4: stop heartbeat / lease-renewal schedulers within budget.

    Looks up schedulers under several attribute conventions:
    - ``client.schedulers``: iterable (Lane S provisional run_worker)
    - ``client._heartbeat`` + ``client._lease``: individual fields
      (Lane T BatchPlatformClient)
    """
    schedulers_iter = getattr(client, "schedulers", None)
    schedulers: list[Any] = list(schedulers_iter) if schedulers_iter else []
    for attr in ("_heartbeat", "_lease"):
        s = getattr(client, attr, None)
        if s is not None and s not in schedulers:
            schedulers.append(s)
    if not schedulers:
        return
    # Run scheduler stops concurrently — they're independent.
    per_call = max(budget, 0.001)
    coros = []
    targets = []
    for s in schedulers:
        stop = getattr(s, "stop", None)
        if stop is None:
            continue
        coros.append(asyncio.wait_for(stop(), timeout=per_call))
        targets.append(s)
    if not coros:
        return
    results = await asyncio.gather(*coros, return_exceptions=True)
    for s, result in zip(targets, results, strict=False):
        if isinstance(result, Exception):
            logger.warning(
                "stop_with_timeout: scheduler %s stop failed: %s; continuing",
                type(s).__name__,
                result,
            )


async def _phase_deactivate(client: Any) -> None:
    """Best-effort POST /deactivate. Never blocks shutdown on failure.

    Matches fixture 12 ``sdkMustNot``: "block stop() if /deactivate
    HTTP fails (log + continue exit)".

    Looks up the deactivate hook under several conventions:
    - ``client.deactivate`` — user-supplied method
    - ``client._http.deactivate`` / ``client.http.deactivate`` — Lane T's
      BatchPlatformClient stores the HTTP client as ``_http`` and exposes
      it via a ``http`` property. Calls with ``(worker_code, body)`` if it
      accepts args, else no-arg.
    """
    deactivate = getattr(client, "deactivate", None)
    if deactivate is None:
        http = getattr(client, "_http", None) or getattr(client, "http", None)
        if http is None:
            return
        deactivate = getattr(http, "deactivate", None)
        if deactivate is None:
            return
    try:
        config = getattr(client, "config", None) or getattr(client, "_config", None)
        if config is not None:
            worker_code = getattr(config, "worker_code", "")
            tenant_id = getattr(config, "tenant_id", "")
            await deactivate(worker_code, {"tenantId": tenant_id, "workerCode": worker_code})
        else:
            await deactivate()
    except Exception as exc:
        logger.warning("stop_with_timeout: /deactivate call failed (ignored): %s", exc)


# ASYNC109: yes, we accept a ``timeout`` parameter on an async function —
# this is exactly the multi-phase budget-split contract documented above.
# ``asyncio.timeout`` cancels the whole coroutine, which would defeat the
# graceful-shutdown semantics (we MUST run /deactivate even after drain
# overruns). Suppress the lint at the function call-site.
async def stop_with_timeout(
    client: Any,
    timeout: float,  # noqa: ASYNC109
) -> None:
    """Shut down ``client`` in phases, respecting a total ``timeout`` budget.

    See module docstring for the phase split. Phases that overrun
    log WARN but never raise — the function always returns within
    ~``timeout`` seconds (plus a small jitter for the final
    /deactivate, which has no per-phase deadline because the contract
    fixture mandates an attempt even after timeout exhaustion).

    :param client: a ``BatchPlatformClient`` instance (Lane T).
        Duck-typed; see module docstring for the expected attribute
        contract. Typed as :class:`Any` rather than imported to break
        a Lane-T circular dependency.
    :param timeout: total wall-clock budget in seconds. Must be
        positive; values < 0.01 are clamped to 0.01s for sanity.
    """
    if timeout <= 0:
        raise ValueError(f"stop_with_timeout: timeout must be positive, got {timeout!r}")
    timeout = max(timeout, 0.01)

    loop = asyncio.get_event_loop()
    start = loop.time()
    overall_deadline = start + timeout

    logger.info("stop_with_timeout: starting phased shutdown (timeout=%.3fs)", timeout)

    # Phase 1: flip draining flag — synchronous, zero budget.
    dispatcher = getattr(client, "dispatcher", None)
    if dispatcher is not None:
        start_draining = getattr(dispatcher, "start_draining", None)
        if callable(start_draining):
            try:
                result = start_draining()
                if asyncio.iscoroutine(result):
                    await result
            except Exception as exc:
                logger.warning("stop_with_timeout: start_draining() failed: %s", exc)
        else:
            # Fall back to a known attribute name if no method exists.
            with contextlib.suppress(Exception):
                dispatcher._draining = True

    # Phase 2: Kafka consumer.
    kafka_budget = _remaining(overall_deadline) * _KAFKA_FRACTION
    await _phase_stop_kafka(client, kafka_budget)

    # Phase 3: dispatcher drain. Deadline = now + (remaining * 60%).
    remaining = _remaining(overall_deadline)
    drain_deadline = loop.time() + remaining * _DRAIN_FRACTION / (
        _DRAIN_FRACTION + _SCHEDULER_FRACTION
    )
    await _phase_drain_in_flight(client, drain_deadline)

    # Phase 4: schedulers.
    sched_budget = _remaining(overall_deadline)
    await _phase_stop_schedulers(client, sched_budget)

    # Phase 5 (out-of-budget): /deactivate — best effort.
    await _phase_deactivate(client)

    elapsed = loop.time() - start
    logger.info("stop_with_timeout: completed in %.3fs (budget=%.3fs)", elapsed, timeout)
