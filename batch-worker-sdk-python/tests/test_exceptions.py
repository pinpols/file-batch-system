"""``batch_worker_sdk.exceptions`` 的直接单测。

之前异常类型只在 ``test_retry.py`` / ``test_http_client.py`` 端到端用到,
缺独立单测。这里点对点验证:

- 4 个异常子类都继承 ``PlatformError``,可被 ``except PlatformError:`` 统一捕获
- 各字段(``status_code`` / ``code`` / ``message`` / ``request_id``)在构造时透传
- ``PersistentClientError.attempts`` / ``TransientError.attempts`` / ``last_error`` 透传
- ``parse_error_body`` 兼容 ``traceId`` / ``trace_id`` / ``requestId`` 三种封包,
  非 dict 返回三个 None

Java 对应:``com.example.batch.sdk.internal.PlatformHttpExceptionTest`` —— 那
里以单个 ``PlatformHttpException`` + ``isXxx()`` 谓词建模,Python 端拆成子类
层次,因此本测试按子类切分。
"""

from __future__ import annotations

import pytest

from batch_worker_sdk.exceptions import (
    AuthError,
    ConflictError,
    PersistentClientError,
    PlatformError,
    TransientError,
    parse_error_body,
)

# ─── 子类层次:统一 except PlatformError 能抓 ──────────────────────────────


@pytest.mark.parametrize(
    "exc_cls",
    [AuthError, ConflictError, PersistentClientError, TransientError],
)
def test_all_subclasses_are_platform_error(exc_cls: type[PlatformError]) -> None:
    assert issubclass(exc_cls, PlatformError)


def test_except_platform_error_catches_each_subclass() -> None:
    # 模拟调用方写 `except PlatformError:` 时,所有分类都被捕获。
    raised: list[type[PlatformError]] = []
    for exc_cls in (AuthError, ConflictError, PersistentClientError, TransientError):
        try:
            raise exc_cls("boom")
        except PlatformError as exc:
            raised.append(type(exc))
    assert raised == [AuthError, ConflictError, PersistentClientError, TransientError]


# ─── 字段透传 ────────────────────────────────────────────────────────────


def test_auth_error_preserves_diagnostic_fields() -> None:
    exc = AuthError(
        "token expired",
        status_code=401,
        code="AUTH_INVALID",
        request_id="trace-123",
    )

    assert exc.message == "token expired"
    assert exc.status_code == 401
    assert exc.code == "AUTH_INVALID"
    assert exc.request_id == "trace-123"
    # ``str(exc)`` 走 ``Exception`` 默认 → ``message``。
    assert str(exc) == "token expired"


def test_conflict_error_can_be_built_without_optional_fields() -> None:
    exc = ConflictError("already claimed")

    assert exc.status_code is None
    assert exc.code is None
    assert exc.request_id is None
    assert exc.message == "already claimed"


def test_persistent_client_error_records_attempts() -> None:
    exc = PersistentClientError(
        "rejected 5 times",
        status_code=400,
        code="VALIDATION",
        request_id="t-1",
        attempts=5,
    )

    assert exc.attempts == 5
    assert exc.status_code == 400
    assert exc.code == "VALIDATION"


def test_persistent_client_error_attempts_default_zero() -> None:
    exc = PersistentClientError("boom")
    assert exc.attempts == 0


def test_transient_error_carries_last_error_chain() -> None:
    underlying = ConnectionError("DNS resolve failed")
    exc = TransientError(
        "5xx exhausted",
        status_code=503,
        attempts=3,
        last_error=underlying,
    )

    assert exc.attempts == 3
    assert exc.last_error is underlying
    assert exc.status_code == 503


def test_transient_error_last_error_default_none() -> None:
    exc = TransientError("timeout")
    assert exc.last_error is None
    assert exc.attempts == 0


# ─── parse_error_body:BizException 信封解析 ───────────────────────────────


def test_parse_error_body_extracts_full_envelope() -> None:
    body = {"code": "USER_NOT_FOUND", "message": "no such user", "traceId": "t-abc"}

    code, message, trace_id = parse_error_body(body)

    assert code == "USER_NOT_FOUND"
    assert message == "no such user"
    assert trace_id == "t-abc"


def test_parse_error_body_falls_back_to_legacy_trace_id_key() -> None:
    # 老路径用 ``trace_id``(下划线)而不是 camelCase ``traceId``。
    code, message, trace_id = parse_error_body(
        {"code": "X", "message": "m", "trace_id": "legacy-id"}
    )

    assert code == "X"
    assert message == "m"
    assert trace_id == "legacy-id"


def test_parse_error_body_falls_back_to_request_id_key() -> None:
    _, _, trace_id = parse_error_body(
        {"code": "X", "message": "m", "requestId": "req-9"}
    )

    assert trace_id == "req-9"


def test_parse_error_body_prefers_trace_id_over_request_id_when_both_present() -> None:
    # traceId 优先级最高,然后 trace_id,最后 requestId。
    _, _, trace_id = parse_error_body(
        {"code": "X", "traceId": "primary", "trace_id": "legacy", "requestId": "fallback"}
    )

    assert trace_id == "primary"


def test_parse_error_body_missing_fields_return_none() -> None:
    code, message, trace_id = parse_error_body({})

    assert code is None
    assert message is None
    assert trace_id is None


def test_parse_error_body_non_dict_returns_all_none() -> None:
    # body 是字符串 / list / None 都视为不可解析。
    for bad in ("error string", ["a", "b"], None, 42):
        assert parse_error_body(bad) == (None, None, None)


def test_parse_error_body_ignores_non_string_field_values() -> None:
    # code/message 必须是 str,否则当 None 处理(避免类型混乱传到调用方)。
    result = parse_error_body(
        {"code": 42, "message": ["not", "a", "string"], "traceId": {"nested": "x"}}
    )

    assert result == (None, None, None)


# ─── repr smoke ─────────────────────────────────────────────────────────


def test_platform_error_repr_includes_classification_fields() -> None:
    exc = AuthError("denied", status_code=403, code="FORBIDDEN", request_id="r-1")
    rendered = repr(exc)

    assert "AuthError" in rendered
    assert "403" in rendered
    assert "FORBIDDEN" in rendered
    assert "r-1" in rendered
    assert "denied" in rendered
