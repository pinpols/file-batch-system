"""progress/checkpoint payload 的敏感关键字检测。

对齐 Java SDK 的 ``SensitiveDataValidator`` —— DB 口令 / OAuth secret /
API key 等绝不允许泄漏到 ``details``,因为平台会把 ``details`` 持久化到
``job_task`` 并通过 console 暴露给前端。

关键字集合的单一权威源是 ``docs/sdk/shared-constants.yaml``(由 drift-guard
parity 测试守护)—— 但 SDK 运行时不能依赖 YAML 解析或文件 IO,因此这里以
**小写 token 的 frozen tuple** 形式镜像同一列表;YAML 漂移时 parity 测试
会报警。

检测刻意 **保守**(子串匹配 + 大小写不敏感):``my_api_key`` 这种 key 在
去除非字母数字字符后包含 ``apikey``,会被命中。宁可误报也不漏报 —— 过激
拒绝只是开发者体验问题,而一次凭据外泄就是安全事故。
"""

from __future__ import annotations

from collections.abc import Iterable
from typing import Final

# 与 docs/sdk/shared-constants.yaml :: sensitive_keywords 保持一致。
# 全小写、无分隔符 —— 子串匹配前先把输入 key 的非字母数字字符剥除,
# 这样 "api-key" / "api_key" / "apiKey" 都会归一为 "apikey"。
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
    """转小写并剥除非字母数字字符,便于子串匹配。"""
    return "".join(ch for ch in key.lower() if ch.isalnum())


def is_sensitive_key(key: str) -> bool:
    """``key`` 看起来像凭据字段时返回 ``True``。

    在归一化后的形式上做子串匹配,所以下列形式都会命中:``password`` /
    ``db_password`` / ``DB-PASSWORD`` / ``apiKey`` / ``my.api.key``。
    """
    normalized = _normalize(key)
    return any(kw in normalized for kw in SENSITIVE_KEYWORDS)


def find_sensitive_keys(keys: Iterable[str]) -> list[str]:
    """返回 ``keys`` 中所有疑似敏感字段的 key。

    无命中时返回空列表;调用方可在结果非空时直接 ``raise``。
    """
    return [k for k in keys if is_sensitive_key(k)]
