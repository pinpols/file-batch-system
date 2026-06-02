"""Retry / exponential-backoff helper for protocol-layer HTTP calls.

Implements the state machine from ``docs/sdk/wire-protocol.md §C``
verbatim. Single coroutine ``with_retry`` is used by every endpoint in
``_http.py``; no decorator magic (decorators + async + httpx ``Response``
re-entry are awkward — a plain ``async def`` reads better and keeps
``mypy --strict`` happy).

Behaviour matrix (mirrors Java ``PlatformHttpException.is*()`` helpers):

================  ==================================================
HTTP / transport  Action
================  ==================================================
2xx               return response body (dict)
401 / 403         raise ``AuthError`` immediately (no retry)
404               raise ``PersistentClientError`` (caller decides;
                  fixture set treats 404 as "missing", but at
                  protocol layer we surface it as a typed error and
                  do NOT increment the cumulative 4xx counter — 404
                  on worker_code lookup is "platform cleaned us"
                  rather than schema drift)
409               return ``{}`` (or response body if non-empty);
                  treated as idempotent success per §B
4xx (other)       increment cumulative client-error counter; if it
                  exceeds ``client_error_fail_fast_threshold`` raise
                  ``PersistentClientError``; else raise on this
                  attempt with ``attempts=`` snapshot for the caller
                  (single attempt — no retry)
5xx / transport   exponential backoff ``base * 2^(attempt-1)``,
                  up to ``max_attempts``; raise ``TransientError``
                  on exhaustion
================  ==================================================

The cumulative 4xx counter is a module-level ``ClientErrorCounter``
object passed in by the http client (one per ``PlatformHttpClient``
instance — matches Java SDK where the counter lives on the
``TaskDispatcher`` singleton).
"""

from __future__ import annotations

import asyncio
import random
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

import httpx

from batch_worker_sdk.exceptions import (
    AuthError,
    PersistentClientError,
    TransientError,
    parse_error_body,
)


@dataclass
class ClientErrorCounter:
    """Sliding counter of consecutive non-auth 4xx errors.

    Mirrors Java ``TaskDispatcher.clientErrorCounter`` (P7-2). Any 2xx
    or 409 idempotent success resets it; 5xx / transport errors do NOT
    reset (they are not "client schema problems").
    """

    threshold: int = 5
    count: int = 0
    fatal: bool = field(default=False)

    def record_client_error(self) -> int:
        """Bump counter, return post-increment value."""
        self.count += 1
        if self.threshold > 0 and self.count >= self.threshold:
            self.fatal = True
        return self.count

    def reset(self) -> None:
        self.count = 0


# Request factory returns a *fresh* awaitable on each call so we can
# re-invoke it for retries without the caller having to know about
# httpx ``Request`` mutation semantics.
RequestFactory = Callable[[], Awaitable[httpx.Response]]


async def with_retry(
    request_factory: RequestFactory,
    *,
    max_attempts: int = 3,
    base_delay_s: float = 0.2,
    counter: ClientErrorCounter | None = None,
    jitter: bool = True,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> httpx.Response:
    """Execute ``request_factory`` with the wire-protocol §C policy.

    Returns the successful ``httpx.Response`` (2xx **or** 409). 409 is
    surfaced so the caller can branch on it — the response body is
    semantically meaningful (e.g. ``code=ALREADY_CLAIMED``).

    Raises:
        AuthError: on first 401 / 403, no retry.
        PersistentClientError: on 4xx (other than 401/403/409) — single
            attempt; if cumulative counter exceeds threshold the error
            is flagged ``fatal=True`` via the counter.
        TransientError: on 5xx / transport after ``max_attempts``.
    """
    counter = counter if counter is not None else ClientErrorCounter()
    last_exc: BaseException | None = None
    last_status: int | None = None
    last_code: str | None = None
    last_message: str | None = None
    last_request_id: str | None = None

    for attempt in range(1, max_attempts + 1):
        try:
            resp = await request_factory()
        except (httpx.TransportError, httpx.TimeoutException) as exc:
            last_exc = exc
            last_status = None
            last_code = None
            last_message = str(exc) or exc.__class__.__name__
            last_request_id = None
            if attempt >= max_attempts:
                break
            await _backoff(sleep, base_delay_s, attempt, jitter)
            continue

        status = resp.status_code

        if 200 <= status < 300:
            counter.reset()
            return resp

        # parse error envelope once
        try:
            body = resp.json()
        except (ValueError, httpx.DecodingError):
            body = None
        code, message, request_id = parse_error_body(body)

        if status in (401, 403):
            raise AuthError(
                message or f"HTTP {status} auth failure",
                status_code=status,
                code=code,
                request_id=request_id,
            )

        if status == 409:
            # treat as idempotent success — caller decides what to do
            # with the body. Counter resets (we successfully reached
            # the platform and got a deterministic answer).
            counter.reset()
            return resp

        if 400 <= status < 500:
            # 404 included here per wire-protocol §B "log warn, give up"
            # — single-attempt, surfaces as PersistentClientError but
            # does NOT poison the counter for 404 specifically (a stale
            # workerCode after ops cleanup is recoverable on next
            # register tick).
            attempts_after = counter.count
            if status != 404:
                attempts_after = counter.record_client_error()
            raise PersistentClientError(
                message or f"HTTP {status} client error",
                status_code=status,
                code=code,
                request_id=request_id,
                attempts=attempts_after,
            )

        # 5xx → retry with backoff
        last_status = status
        last_code = code
        last_message = message
        last_request_id = request_id
        last_exc = None
        if attempt >= max_attempts:
            break
        await _backoff(sleep, base_delay_s, attempt, jitter)

    # Exhausted — raise TransientError
    raise TransientError(
        last_message or (f"HTTP {last_status} server error" if last_status else "transport error"),
        status_code=last_status,
        code=last_code,
        request_id=last_request_id,
        attempts=max_attempts,
        last_error=last_exc,
    )


async def _backoff(
    sleep: Callable[[float], Awaitable[None]],
    base_delay_s: float,
    attempt: int,
    jitter: bool,
) -> None:
    """Sleep ``base * 2^(attempt-1)`` seconds, optionally with ±10% jitter."""
    delay = base_delay_s * (2 ** (attempt - 1))
    if jitter:
        delay *= 1.0 + (random.random() - 0.5) * 0.2
    await sleep(delay)
