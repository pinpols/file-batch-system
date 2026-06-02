"""Sensitive-keyword detection for progress/checkpoint payloads.

Mirrors the Java SDK's ``SensitiveDataValidator`` (Lane C — DB password
/ OAuth secret / API key must NEVER leak into ``details``, which the
platform persists to ``job_task`` and exposes via console).

Single source of truth for the keyword set is
``docs/sdk/shared-constants.yaml`` (Lane P drift-guard) — but at runtime
the SDK must not depend on YAML parsing or file IO. We mirror the list
here as a frozen tuple of **lowercase tokens**; the Lane P parity test
catches drift if the YAML changes.

The check is intentionally **conservative** (substring match,
case-insensitive): a key called ``my_api_key`` triggers because it
contains ``apikey`` after stripping non-alphanumerics. We choose
false-positive-friendly over false-negative-friendly: an over-eager
reject is a developer ergonomics issue, but a missed credential leak
is a security incident.
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Final

# Keep in sync with docs/sdk/shared-constants.yaml :: sensitive_keywords.
# Lowercase, no separators — we strip non-alphanumerics from the input
# key before substring matching, so "api-key" / "api_key" / "apiKey"
# all collapse to "apikey".
SENSITIVE_KEYWORDS: Final[tuple[str, ...]] = (
    "password",
    "passwd",
    "secret",
    "token",
    "credential",
    "apikey",
    "privatekey",
    "accesskey",
)


def _normalize(key: str) -> str:
    """Lowercase + strip non-alphanumeric characters for substring match."""
    return "".join(ch for ch in key.lower() if ch.isalnum())


def is_sensitive_key(key: str) -> bool:
    """Return ``True`` if ``key`` looks like a credential field.

    The check is substring-based on the normalized form so the
    following all match: ``password`` / ``db_password`` /
    ``DB-PASSWORD`` / ``apiKey`` / ``my.api.key``.
    """
    normalized = _normalize(key)
    return any(kw in normalized for kw in SENSITIVE_KEYWORDS)


def find_sensitive_keys(keys: Iterable[str]) -> list[str]:
    """Return every key in ``keys`` that looks sensitive.

    Returns an empty list when none match; callers can ``raise`` on a
    non-empty result.
    """
    return [k for k in keys if is_sensitive_key(k)]
