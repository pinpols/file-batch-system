"""内置文件 handler 共享的定界符(CSV 类)编解码。

对齐 Java ``com.example.batch.sdk.handler.builtin.support.DelimitedFormat``
与 ``DelimitedCodec`` —— RFC4180 风格的单物理行一记录。

保持包内私有(``_delimited``)放在 :mod:`handler.builtin` 下,因为 Java 对应
物在 ``builtin/support/`` 也不属于 SDK 公共 API;租户通过 :class:`FileImportConfig`
/ :class:`QueryExportConfig` 组合使用,不应直接 import 这个 codec。
"""

from __future__ import annotations

from collections.abc import Sequence

from pydantic import BaseModel, ConfigDict, Field


class DelimitedFormat(BaseModel):
    """FileImport / QueryExport 共享的定界文件格式。

    对齐 Java ``DelimitedFormat`` record。默认值对齐 RFC4180 CSV
    (逗号 + 双引号 + 含 header)。
    """

    model_config = ConfigDict(frozen=True, extra="forbid")

    delimiter: str = Field(default=",", min_length=1, max_length=1)
    """字段分隔符(单字符)。默认 ``,``。"""

    quote: str = Field(default='"', min_length=1, max_length=1)
    """引号字符(单字符)。默认 ``"``。"""

    header: bool = True
    """Import:跳过首行;Export:输出列名 header 行。默认 ``True``。"""

    @classmethod
    def defaults(cls) -> DelimitedFormat:
        """默认 RFC4180 CSV 格式。"""
        return cls()


def parse_line(line: str, delimiter: str, quote: str) -> list[str]:
    """解析单个物理行为字段列表。

    对齐 Java ``DelimitedCodec.parse``。被 ``quote`` 包裹的字段内部可以含字面
    定界符;引号字段内的 ``quote+quote`` 表示一个字面引号。不支持引号字段内
    的换行(一物理行 == 一记录)。
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
    """将字段列表编码为一个物理行(按需加引号 / 转义)。

    对齐 Java ``DelimitedCodec.encode``。``None`` 字段编码为空串。包含定界符、
    引号或换行的字段会被 ``quote`` 包裹,内部引号双写转义。
    """
    parts: list[str] = []
    for i, raw in enumerate(fields):
        if i > 0:
            parts.append(delimiter)
        f = "" if raw is None else raw
        need_quote = delimiter in f or quote in f or "\n" in f or "\r" in f
        if need_quote:
            escaped = f.replace(quote, quote + quote)
            parts.append(quote + escaped + quote)
        else:
            parts.append(f)
    return "".join(parts)
