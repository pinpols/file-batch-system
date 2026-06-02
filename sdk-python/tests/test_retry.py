"""Unit tests for the wire-protocol §C retry / backoff state machine.

Each test calls ``with_retry`` against a scripted ``request_factory``
that yields a sequence of ``httpx.Response`` objects (or raises
``httpx.TransportError``). We inject a fake ``sleep`` so the suite
runs in milliseconds and we can assert the back-off schedule.
"""

from __future__ import annotations

import httpx
import pytest

from batch_worker_sdk._retry import ClientErrorCounter, with_retry
from batch_worker_sdk.exceptions import (
    AuthError,
    PersistentClientError,
    TransientError,
)


def _resp(status: int, body: dict | None = None) -> httpx.Response:
    return httpx.Response(status_code=status, json=body or {})


class _Sleeps:
    """Capture awaited sleep durations without actually sleeping."""

    def __init__(self) -> None:
        self.calls: list[float] = []

    async def __call__(self, seconds: float) -> None:
        self.calls.append(seconds)


def _factory(responses):
    """Return a request_factory yielding ``responses`` in order. Items
    may be ``httpx.Response`` or exception instances (will be raised)."""
    it = iter(responses)

    async def factory() -> httpx.Response:
        item = next(it)
        if isinstance(item, BaseException):
            raise item
        return item

    return factory


async def test_2xx_returns_response_no_sleep():
    sleeps = _Sleeps()
    resp = await with_retry(
        _factory([_resp(200, {"ok": True})]),
        max_attempts=3,
        base_delay_s=0.2,
        sleep=sleeps,
        jitter=False,
    )
    assert resp.status_code == 200
    assert sleeps.calls == []


async def test_401_raises_auth_error_immediately():
    sleeps = _Sleeps()
    with pytest.raises(AuthError) as ei:
        await with_retry(
            _factory([_resp(401, {"code": "AUTH_INVALID", "traceId": "t-1"})]),
            max_attempts=3,
            base_delay_s=0.2,
            sleep=sleeps,
            jitter=False,
        )
    assert ei.value.status_code == 401
    assert ei.value.code == "AUTH_INVALID"
    assert ei.value.request_id == "t-1"
    assert sleeps.calls == []  # zero retries


async def test_403_also_auth_error():
    with pytest.raises(AuthError):
        await with_retry(
            _factory([_resp(403, {"code": "TENANT_FORBIDDEN"})]),
            max_attempts=3,
            base_delay_s=0.2,
            sleep=_Sleeps(),
            jitter=False,
        )


async def test_409_returns_response_as_idempotent_success():
    sleeps = _Sleeps()
    resp = await with_retry(
        _factory([_resp(409, {"code": "ALREADY_CLAIMED"})]),
        max_attempts=3,
        base_delay_s=0.2,
        sleep=sleeps,
        jitter=False,
    )
    assert resp.status_code == 409
    assert sleeps.calls == []


async def test_4xx_other_raises_persistent_error_single_attempt():
    sleeps = _Sleeps()
    counter = ClientErrorCounter(threshold=5)
    with pytest.raises(PersistentClientError) as ei:
        await with_retry(
            _factory([_resp(422, {"code": "VALIDATION_FAILED"})]),
            max_attempts=3,
            base_delay_s=0.2,
            counter=counter,
            sleep=sleeps,
            jitter=False,
        )
    assert ei.value.status_code == 422
    assert sleeps.calls == []
    assert counter.count == 1
    assert counter.fatal is False


async def test_4xx_accumulated_to_threshold_marks_fatal():
    counter = ClientErrorCounter(threshold=3)
    for i in range(3):
        with pytest.raises(PersistentClientError):
            await with_retry(
                _factory([_resp(400, {"code": "BAD"})]),
                max_attempts=1,
                base_delay_s=0.2,
                counter=counter,
                sleep=_Sleeps(),
                jitter=False,
            )
        assert counter.count == i + 1
    assert counter.fatal is True


async def test_5xx_retries_with_exponential_backoff_then_exhausts():
    sleeps = _Sleeps()
    with pytest.raises(TransientError) as ei:
        await with_retry(
            _factory(
                [
                    _resp(503, {"code": "DB_DOWN"}),
                    _resp(503, {"code": "DB_DOWN"}),
                    _resp(503, {"code": "DB_DOWN"}),
                ]
            ),
            max_attempts=3,
            base_delay_s=0.2,
            sleep=sleeps,
            jitter=False,
        )
    assert ei.value.status_code == 503
    assert ei.value.attempts == 3
    # 3 attempts → 2 sleeps (after attempts 1 and 2) of 200ms and 400ms
    assert len(sleeps.calls) == 2
    assert sleeps.calls[0] == pytest.approx(0.2)
    assert sleeps.calls[1] == pytest.approx(0.4)


async def test_5xx_recovers_on_second_attempt():
    sleeps = _Sleeps()
    resp = await with_retry(
        _factory([_resp(500), _resp(200, {"recovered": True})]),
        max_attempts=3,
        base_delay_s=0.2,
        sleep=sleeps,
        jitter=False,
    )
    assert resp.status_code == 200
    assert sleeps.calls == pytest.approx([0.2])


async def test_transport_error_retries_then_raises_transient():
    sleeps = _Sleeps()
    err = httpx.ConnectError("connection refused")
    with pytest.raises(TransientError) as ei:
        await with_retry(
            _factory([err, err, err]),
            max_attempts=3,
            base_delay_s=0.2,
            sleep=sleeps,
            jitter=False,
        )
    assert ei.value.last_error is err
    assert ei.value.attempts == 3
    assert len(sleeps.calls) == 2


async def test_2xx_resets_client_error_counter():
    counter = ClientErrorCounter(threshold=5)
    counter.count = 3
    await with_retry(
        _factory([_resp(200, {})]),
        max_attempts=3,
        base_delay_s=0.2,
        counter=counter,
        sleep=_Sleeps(),
        jitter=False,
    )
    assert counter.count == 0


async def test_404_does_not_poison_counter():
    counter = ClientErrorCounter(threshold=5)
    with pytest.raises(PersistentClientError) as ei:
        await with_retry(
            _factory([_resp(404, {"code": "NOT_FOUND"})]),
            max_attempts=3,
            base_delay_s=0.2,
            counter=counter,
            sleep=_Sleeps(),
            jitter=False,
        )
    assert ei.value.status_code == 404
    assert counter.count == 0  # 404 specifically does not increment
