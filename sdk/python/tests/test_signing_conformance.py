"""请求签名(方案 A)契约一致性 —— 必须与服务端 / 其他语言 SDK 逐字节一致。

权威 golden 向量来自跨 SDK 契约(见 Java ``RequestSignerConformanceTest`` /
服务端 ``RequestSignatures``)。任一边改了 canonical 串 / HMAC 细节,本测试立刻红。

算法:

    canonical = UPPER(method) "\\n" path "\\n" timestamp "\\n" nonce "\\n" hex(sha256(body))
    signature = hex(hmacSha256(apiKey, canonical))     # 小写 hex
"""

from __future__ import annotations

import re

from batch_worker_sdk.internal import _signing

# ─── golden 向量(唯一权威,逐字节断言) ──────────────────────────────
_API_KEY = "golden-key"
_METHOD = "POST"
_PATH = "/internal/tasks/42/report"
_TIMESTAMP = "1700000000000"
_NONCE = "golden-nonce"
_BODY = b'{"tenantId":"t1","success":true}'

_GOLDEN_BODY_SHA256 = "c9a04b2061b2c381193ee868b9d89bc16979c738d257f8495d18457a83462dd5"
_GOLDEN_SIGNATURE = "287108832407aec1bc689c97ac22037b7114b2702671dfb20d1aacc6edeb0898"
_GOLDEN_EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"


def test_body_sha256_matches_golden() -> None:
    assert _signing.body_sha256_hex(_BODY) == _GOLDEN_BODY_SHA256


def test_empty_body_sha256_matches_golden() -> None:
    assert _signing.body_sha256_hex(b"") == _GOLDEN_EMPTY_SHA256
    # None 等价于空 body(契约要求空 body 也算 sha256)。
    assert _signing.body_sha256_hex(None) == _GOLDEN_EMPTY_SHA256


def test_signature_matches_golden() -> None:
    sig = _signing.sign(_API_KEY, _METHOD, _PATH, _TIMESTAMP, _NONCE, _BODY)
    assert sig == _GOLDEN_SIGNATURE
    assert re.fullmatch(r"[0-9a-f]{64}", sig)


def test_canonical_string_shape() -> None:
    canonical = _signing.canonical_string(_METHOD, _PATH, _TIMESTAMP, _NONCE, _BODY)
    assert canonical == (f"{_METHOD}\n{_PATH}\n{_TIMESTAMP}\n{_NONCE}\n{_GOLDEN_BODY_SHA256}")


def test_method_is_uppercased() -> None:
    # UPPER(method) —— 小写 method 必须产出与大写相同的签名。
    lower = _signing.sign(_API_KEY, "post", _PATH, _TIMESTAMP, _NONCE, _BODY)
    assert lower == _GOLDEN_SIGNATURE
