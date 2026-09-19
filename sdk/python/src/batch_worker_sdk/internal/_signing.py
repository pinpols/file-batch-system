"""SDK 侧请求签名(方案 A,以 api_key 为 HMAC 密钥)。

对齐 Java ``io.github.pinpols.batch.sdk.internal.RequestSigner`` 与服务端
``io.github.pinpols.batch.common.security.RequestSignatures`` —— 三者必须**逐字节**一致,
由各语言 SDK 的契约一致性(conformance)用例钉死;任一边改了 canonical 串 / HMAC 细节,
测试立刻红。算法:

    canonical = UPPER(method) "\\n" path "\\n" timestamp "\\n" nonce "\\n" hex(sha256(body))
    signature = hex(hmacSha256(apiKey, canonical))     # 小写 hex

仅用标准库 ``hashlib`` / ``hmac``,不引入任何第三方依赖。
"""

from __future__ import annotations

import hashlib
import hmac


def body_sha256_hex(body: bytes | None) -> str:
    """body 原始 bytes 的 SHA-256,小写 hex(空 body 也算)。"""
    return hashlib.sha256(body or b"").hexdigest()


def canonical_string(
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    body: bytes | None,
) -> str:
    """拼装待签名的规范串 —— 与服务端 / Java SDK 逐字节一致。"""
    return "\n".join(
        (
            (method or "").upper(),
            path or "",
            timestamp or "",
            nonce or "",
            body_sha256_hex(body),
        )
    )


def sign(
    api_key: str,
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    body: bytes | None,
) -> str:
    """HMAC-SHA256(apiKey, canonical) 的小写 hex。"""
    canonical = canonical_string(method, path, timestamp, nonce, body)
    return hmac.new(
        (api_key or "").encode("utf-8"),
        canonical.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
