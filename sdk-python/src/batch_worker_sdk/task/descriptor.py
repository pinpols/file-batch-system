"""Custom task-type descriptor (P0.5 stub).

Mirrors Java ``com.example.batch.sdk.task.SdkTaskTypeDescriptor``.
Returned from :meth:`SdkTaskHandler.descriptor` and sent on the
worker-register request body so the console can render parameter forms
and the orchestrator can merge defaults at dispatch time.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class SdkTaskTypeDescriptor(BaseModel):
    """Declarative metadata for a custom task type.

    Field-for-field mirror of the Java record so the wire payload is
    identical when Python workers register against the same orchestrator
    that Java workers do.
    """

    model_config = ConfigDict(frozen=True, extra="forbid", populate_by_name=True)

    task_type: str
    """Authoritative task-type code (matches ``SdkTaskHandler.task_type``)."""

    display_name: str | None = None
    """Console display name (optional)."""

    input_schema: dict[str, Any] | None = Field(default=None, alias="schema")
    """JSON Schema for ``parameters`` — drives form rendering + validation.

    Wire alias ``schema`` (matches Java ``inputSchema``). The Python
    attribute is named ``input_schema`` to avoid shadowing pydantic's
    ``BaseModel.schema`` classmethod.
    """

    parameters: dict[str, Any] | None = None
    """Default parameter values (merged under user-supplied node parameters)."""

    outputs: dict[str, Any] | None = None
    """Declared output shape (informational; future contract enforcement)."""

    required_env: list[str] | None = None
    """Environment variables the handler needs at runtime (e.g. DB creds)."""
