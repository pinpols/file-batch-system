"""Task execution result (P0.5 stub).

Mirrors Java ``com.example.batch.sdk.task.SdkTaskResult``. SDK
framework serializes this to the platform REPORT protocol on handler
return. Atomic Lane K error codes (``AtomicErrorCode``) are written
into ``output`` (e.g. ``output['errorCode'] = 'ATOMIC_TIMEOUT'``) so
the platform-side error taxonomy stays language-agnostic.
"""

from __future__ import annotations

from typing import Any, Self

from pydantic import BaseModel, ConfigDict, Field


class SdkTaskResult(BaseModel):
    """What a handler returns; the SDK turns it into a REPORT call."""

    model_config = ConfigDict(frozen=True, extra="forbid")

    success: bool
    """``True`` → orchestrator marks SUCCESS; ``False`` → FAILED + retry/compensate."""

    output: dict[str, Any] = Field(default_factory=dict)
    """Business output Map — forwarded as downstream ``runtimeAttributes``."""

    message: str | None = None
    """Free-form summary (success) or error text (failure); landed in audit log."""

    @classmethod
    def success_with(
        cls,
        output: dict[str, Any] | None = None,
        message: str | None = None,
    ) -> Self:
        """Build a success result (aligns with Java ``SdkTaskResult.ok``)."""
        return cls(success=True, output=output or {}, message=message or "ok")

    @classmethod
    def fail(
        cls,
        code: str,
        message: str,
        cause: Exception | None = None,
    ) -> Self:
        """Build a failure result with an error code in ``output``.

        ``code`` is placed under ``output['errorCode']`` (aligns with the
        atomic Lane K ``AtomicErrorCode`` convention). ``cause``, when
        provided, is summarized into ``output['errorClass']`` —
        full stacktrace serialization lands in P1+ when the wire adapter
        exists.
        """
        output: dict[str, Any] = {"errorCode": code}
        if cause is not None:
            output["errorClass"] = type(cause).__name__
        return cls(success=False, output=output, message=message)
