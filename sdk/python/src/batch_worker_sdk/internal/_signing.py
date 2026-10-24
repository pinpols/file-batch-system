"""SDK 侧请求签名(方案 A,以 api_key 为 HMAC 密钥)。

对齐 Java ``io.github.pinpols.batch.sdk.internal.RequestSigner`` 与服务端
``io.github.pinpols.batch.common.security.RequestSignatures`` —— 三者必须**逐字节**一致,
由各语言 SDK 的契约一致性(conformance)用例固化;任一边改了 canonical 串 / HMAC 细节,
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
    hash_input = body or b""
    body_digest = hashlib.sha256(hash_input)
    return body_digest.hexdigest()


def canonical_string(
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    body: bytes | None,
) -> str:
    """拼装待签名的规范串 —— 与服务端 / Java SDK 逐字节一致。"""
    normalized_method = (method or "").upper()
    body_digest = body_sha256_hex(body)
    return "\n".join(
        (
            normalized_method,
            path or "",
            timestamp or "",
            nonce or "",
            body_digest,
        )
    )


def sign(
    api_key: str,
    *,
    method: str,
    path: str,
    timestamp: str,
    nonce: str,
    body: bytes | None,
) -> str:
    """HMAC-SHA256(apiKey, canonical) 的小写 hex。"""
    canonical = canonical_string(method, path, timestamp, nonce, body)
    key_bytes = (api_key or "").encode("utf-8")
    canonical_bytes = canonical.encode("utf-8")
    signature = hmac.new(key_bytes, canonical_bytes, hashlib.sha256)
    return signature.hexdigest()
