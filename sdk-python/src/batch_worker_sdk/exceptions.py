"""Exception hierarchy for batch-worker-sdk (Python).

Wire-protocol §B classifies HTTP failures into four buckets that the SDK
must distinguish. We model that classification as concrete exception
subclasses so callers can ``except AuthError:`` / ``except TransientError:``
instead of inspecting status codes by hand.

Java equivalent: ``com.example.batch.sdk.internal.PlatformHttpException``
plus its ``isAuthError() / isConflict() / isServerError()`` helpers — we
collapse those predicates into the class hierarchy.

All errors carry the same diagnostic surface:

- ``status_code``  : HTTP status (``None`` for transport-layer errors)
- ``code``         : BE BizException i18n key (``AUTH_INVALID`` etc.)
- ``message``      : human-readable detail
- ``request_id``   : ``traceId`` from BE error body when present
"""

from __future__ import annotations

from typing import Any


class PlatformError(Exception):
    """Base for every protocol-level error this SDK raises.

    Generic ``except PlatformError:`` catches every classified failure
    while still letting truly unexpected ``Exception`` (programmer
    errors, ``KeyboardInterrupt`` etc.) propagate untouched.
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        code: str | None = None,
        request_id: str | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.request_id = request_id

    def __repr__(self) -> str:  # pragma: no cover - trivial
        return (
            f"{self.__class__.__name__}(status_code={self.status_code!r}, "
            f"code={self.code!r}, request_id={self.request_id!r}, "
            f"message={self.message!r})"
        )


class AuthError(PlatformError):
    """401 / 403 — credentials invalid / tenant scope violation.

    Per wire-protocol §B these MUST fail-fast: no retry, mark dispatcher
    fatal. The retry helper raises this directly on the first hit.
    """


class ConflictError(PlatformError):
    """409 — idempotent-already-applied (task already claimed etc.).

    Per §B treated as success: caller logs INFO and proceeds. We still
    surface this as an exception so callers can branch on it; the retry
    helper does NOT raise it — it converts 409 into a normal return.
    Reserved for cases where caller explicitly wants the response body.
    """


class PersistentClientError(PlatformError):
    """4xx (other than 401/403/404/409) accumulated past the fail-fast
    threshold (default 5).

    Indicates SDK ↔ platform contract drift; retry will not help.
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        code: str | None = None,
        request_id: str | None = None,
        attempts: int = 0,
    ) -> None:
        super().__init__(message, status_code=status_code, code=code, request_id=request_id)
        self.attempts = attempts


class TransientError(PlatformError):
    """5xx / transport-layer error after exponential-backoff budget is
    exhausted (default ``max_attempts=3``: 200ms / 400ms / 800ms).

    Caller should log + drop; next periodic tick (heartbeat / lease) or
    next user action retries naturally.
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        code: str | None = None,
        request_id: str | None = None,
        attempts: int = 0,
        last_error: BaseException | None = None,
    ) -> None:
        super().__init__(message, status_code=status_code, code=code, request_id=request_id)
        self.attempts = attempts
        self.last_error = last_error


def parse_error_body(body: Any) -> tuple[str | None, str | None, str | None]:
    """Best-effort extraction of ``(code, message, trace_id)`` from a BE
    error envelope. BE BizException renders as
    ``{"code": "...", "message": "...", "traceId": "..."}``; older paths
    use ``trace_id``. Returns ``(None, None, None)`` if body is not a
    dict.
    """
    if not isinstance(body, dict):
        return None, None, None
    code = body.get("code")
    message = body.get("message")
    trace_id = body.get("traceId") or body.get("trace_id") or body.get("requestId")
    return (
        code if isinstance(code, str) else None,
        message if isinstance(message, str) else None,
        trace_id if isinstance(trace_id, str) else None,
    )
