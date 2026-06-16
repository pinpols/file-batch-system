"""DelimitedCodec / DelimitedFormat 直接单测。

对齐 Java ``DelimitedCodecTest`` —— RFC4180 风格定界文件的 parse / encode 行为。
之前只在 ``test_file_import.py`` / ``test_query_export.py`` 端到端用到,缺独立
单测;这里把 parse 和 encode 的细则做点对点覆盖,避免被上层 happy-path 屏
蔽掉。

Java 对应:``com.example.batch.sdk.handler.builtin.support.DelimitedCodecTest``。
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from batch_worker_sdk.handler.builtin._delimited import (
    DelimitedFormat,
    encode_line,
    parse_line,
)

# ─── parse ────────────────────────────────────────────────────────────────


def test_parse_plain_fields_splits_on_delimiter() -> None:
    assert parse_line("a,b,c", ",", '"') == ["a", "b", "c"]


def test_parse_quoted_field_containing_delimiter_is_kept_verbatim() -> None:
    # 引号内的逗号是字面量,不参与切分。
    assert parse_line('a,"b,c",d', ",", '"') == ["a", "b,c", "d"]


def test_parse_escaped_quote_inside_quoted_field() -> None:
    # 引号字段内 `""` 表示一个字面引号。
    assert parse_line('"a""b","c"', ",", '"') == ['a"b', "c"]


def test_parse_trailing_empty_field_is_preserved() -> None:
    assert parse_line("a,b,", ",", '"') == ["a", "b", ""]


def test_parse_empty_string_yields_single_empty_field() -> None:
    assert parse_line("", ",", '"') == [""]


def test_parse_supports_custom_delimiter() -> None:
    assert parse_line("a;b;c", ";", '"') == ["a", "b", "c"]


def test_parse_supports_custom_quote_char() -> None:
    # quote 设为 `'`,这样 `'a,b'` 是一个字段。
    assert parse_line("'a,b',c", ",", "'") == ["a,b", "c"]


# ─── encode ──────────────────────────────────────────────────────────────


def test_encode_plain_fields_no_quoting() -> None:
    assert encode_line(["a", "b", "c"], ",", '"') == "a,b,c"


def test_encode_field_with_delimiter_gets_quoted() -> None:
    assert encode_line(["a,b", "c"], ",", '"') == '"a,b",c'


def test_encode_field_with_quote_doubles_it() -> None:
    assert encode_line(['a"b'], ",", '"') == '"a""b"'


def test_encode_field_with_newline_gets_quoted() -> None:
    assert encode_line(["line1\nline2"], ",", '"') == '"line1\nline2"'


def test_encode_none_becomes_empty_field() -> None:
    assert encode_line(["a", None, "c"], ",", '"') == "a,,c"


def test_encode_then_parse_round_trips_through_tricky_inputs() -> None:
    rows: list[list[str]] = [
        ["alpha", "beta", "gamma"],
        ["with,comma", 'with"quote', "with\nnewline"],
        ["", "trailing-empty", ""],
        ["plain", "x", "y"],
    ]
    for row in rows:
        line = encode_line(row, ",", '"')
        decoded = parse_line(line, ",", '"')
        assert decoded == row, f"round-trip lost data: {row!r} → {line!r} → {decoded!r}"


# ─── DelimitedFormat (pydantic model) ────────────────────────────────────


def test_delimited_format_defaults_match_rfc4180_csv() -> None:
    fmt = DelimitedFormat.defaults()
    assert fmt.delimiter == ","
    assert fmt.quote == '"'
    assert fmt.header is True


def test_delimited_format_is_frozen() -> None:
    fmt = DelimitedFormat()
    with pytest.raises(ValidationError):
        # frozen=True → 赋值触发 pydantic ValidationError(而非 AttributeError)。
        fmt.delimiter = ";"


def test_delimited_format_rejects_multi_char_delimiter() -> None:
    with pytest.raises(ValidationError):
        DelimitedFormat(delimiter=",,")


def test_delimited_format_rejects_empty_delimiter() -> None:
    with pytest.raises(ValidationError):
        DelimitedFormat(delimiter="")


def test_delimited_format_rejects_extra_fields() -> None:
    with pytest.raises(ValidationError):
        DelimitedFormat(unknown_field="x")  # type: ignore[call-arg]
