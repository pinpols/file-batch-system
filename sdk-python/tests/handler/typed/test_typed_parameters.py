"""``SdkTypedParameters`` —— pydantic 反序列化辅助类的测试。"""

from __future__ import annotations

import pytest
from pydantic import BaseModel

from batch_worker_sdk.handler.typed import SdkTypedParameters


class _Req(BaseModel):
    source: str
    limit: int = 10


def test_parse_happy_path() -> None:
    out = SdkTypedParameters.parse({"source": "s3://x", "limit": 5}, _Req)
    assert isinstance(out, _Req)
    assert out.source == "s3://x"
    assert out.limit == 5


def test_parse_missing_required_raises_value_error() -> None:
    with pytest.raises(ValueError, match="_Req") as exc:
        SdkTypedParameters.parse({}, _Req)
    assert "source" in str(exc.value)


def test_parse_uses_defaults_when_field_omitted() -> None:
    out = SdkTypedParameters.parse({"source": "s"}, _Req)
    assert out.limit == 10


def test_parse_empty_payload_when_none() -> None:
    class _AllOptional(BaseModel):
        flag: bool = False

    out = SdkTypedParameters.parse(None, _AllOptional)
    assert out.flag is False


def test_serialize_none_returns_empty() -> None:
    assert SdkTypedParameters.serialize(None) == {}


def test_serialize_model_dump() -> None:
    out = SdkTypedParameters.serialize(_Req(source="x", limit=3))
    assert out == {"source": "x", "limit": 3}


def test_parse_requires_basemodel_subclass() -> None:
    with pytest.raises(TypeError):
        SdkTypedParameters.parse({}, dict)  # type: ignore[type-var]
