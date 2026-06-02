"""Shared delimited (CSV-like) codec for builtin file handlers.

Mirrors Java ``com.example.batch.sdk.handler.builtin.support.DelimitedFormat``
and ``DelimitedCodec`` — RFC4180-style single-physical-line records.

Kept package-private (``_delimited``) under :mod:`handler.builtin` because
the Java counterpart lives in ``builtin/support/`` and isn't part of the
public SDK surface; tenants compose via :class:`FileImportConfig` /
:class:`QueryExportConfig`, not by importing the codec directly.
"""

from __future__ import annotations

from collections.abc import Sequence

from pydantic import BaseModel, ConfigDict, Field


class DelimitedFormat(BaseModel):
    """Delimited file format shared by FileImport / QueryExport.

    Mirrors Java ``DelimitedFormat`` record. Defaults match RFC4180 CSV
    (comma + double-quote + header on).
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    delimiter: str = Field(default=",", min_length=1, max_length=1)
    """Field separator (single char). Default ``,``."""

    quote: str = Field(default='"', min_length=1, max_length=1)
    """Quote character (single char). Default ``"``."""

    header: bool = True
    """Import: skip first row; Export: emit column-name header row. Default ``True``."""

    @classmethod
    def defaults(cls) -> DelimitedFormat:
        """Default RFC4180 CSV format."""
        return cls()


def parse_line(line: str, delimiter: str, quote: str) -> list[str]:
    """Parse one physical line into a list of fields.

    Mirrors Java ``DelimitedCodec.parse``. A field wrapped in ``quote`` may
    contain literal delimiters; ``quote+quote`` inside a quoted field
    means a single literal quote. Does not support newlines inside quoted
    fields (one physical line == one record).
    """
    out: list[str] = []
    field_chars: list[str] = []
    in_quotes = False
    i = 0
    n = len(line)
    while i < n:
        c = line[i]
        if in_quotes:
            if c == quote:
                if i + 1 < n and line[i + 1] == quote:
                    field_chars.append(quote)
                    i += 2
                else:
                    in_quotes = False
                    i += 1
            else:
                field_chars.append(c)
                i += 1
        elif c == quote:
            in_quotes = True
            i += 1
        elif c == delimiter:
            out.append("".join(field_chars))
            field_chars.clear()
            i += 1
        else:
            field_chars.append(c)
            i += 1
    out.append("".join(field_chars))
    return out


def encode_line(fields: Sequence[str | None], delimiter: str, quote: str) -> str:
    """Encode field list to one physical line (quoting + escaping as needed).

    Mirrors Java ``DelimitedCodec.encode``. ``None`` fields encode as empty
    string. Fields containing the delimiter, quote, or newlines are wrapped
    in ``quote`` with internal quotes doubled.
    """
    parts: list[str] = []
    for i, raw in enumerate(fields):
        if i > 0:
            parts.append(delimiter)
        f = "" if raw is None else raw
        need_quote = (
            delimiter in f or quote in f or "\n" in f or "\r" in f
        )
        if need_quote:
            escaped = f.replace(quote, quote + quote)
            parts.append(quote + escaped + quote)
        else:
            parts.append(f)
    return "".join(parts)
