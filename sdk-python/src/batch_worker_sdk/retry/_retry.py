"""协议层 HTTP 调用的重试 / 指数退避辅助。

逐字实现 ``docs/sdk/wire-protocol.md §C`` 中的状态机。``_http.py`` 的每个
端点都通过同一个协程 ``with_retry`` 调用 —— 不用装饰器(装饰器 + async +
httpx ``Response`` 复入语义比较绕,纯 ``async def`` 更易读,也更友好
``mypy --strict``)。

行为矩阵(对齐 Java ``PlatformHttpException.is*()`` 等谓词):

================  ==================================================
HTTP / 传输       动作
================  ==================================================
2xx               返回响应体(dict)
401 / 403         立即抛 ``AuthError``(不重试)
404               抛 ``PersistentClientError`` 给调用方决定。fixture
                  把 404 视为"已不存在",但协议层这里以类型化异常
                  暴露,**且不递增**累计 4xx 计数器 —— worker_code
                  查询出 404 通常是"平台清理了 worker",不是 schema
                  漂移
409               返回 ``{}``(或非空时返回响应体);按 §B 视为幂等
                  成功
4xx(其余)        递增累计 client-error 计数器;若超过
                  ``client_error_fail_fast_threshold`` 抛
                  ``PersistentClientError``;否则按本次尝试抛出并把
                  ``attempts=`` 快照交给调用方(本类型单次尝试,不
                  重试)
5xx / 传输        指数退避 ``base * 2^(attempt-1)``,最多
                  ``max_attempts`` 次;耗尽后抛 ``TransientError``
================  ==================================================

累计 4xx 计数器是一个 ``ClientErrorCounter`` 对象,由 http client 注入(每
个 ``PlatformHttpClient`` 实例一个;Java SDK 里同样位于 ``TaskDispatcher``
单例上)。
"""

from __future__ import annotations

import asyncio
import random
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field

import httpx

from batch_worker_sdk.exceptions import (
    AuthError,
    PersistentClientError,
    TransientError,
    parse_error_body,
)


@dataclass
class ClientErrorCounter:
    """连续非 auth 4xx 错误的滑动计数器。

    对齐 Java ``TaskDispatcher.clientErrorCounter``。任何 2xx 或 409 幂等
    成功都会清零;5xx / 传输层错误 **不** 清零(它们不是"客户端 schema
    问题")。
    """

    threshold: int = 5
    count: int = 0
    fatal: bool = field(default=False)

    def record_client_error(self) -> int:
        """递增计数器并返回递增后的值。"""
        self.count += 1
        if self.threshold > 0 and self.count >= self.threshold:
            self.fatal = True
        return self.count

    def reset(self) -> None:
        self.count = 0


# request factory 每次调用都返回一个 **全新** awaitable,这样重试时无需调用
# 方了解 httpx ``Request`` 的可变性语义。
RequestFactory = Callable[[], Awaitable[httpx.Response]]


async def with_retry(
    request_factory: RequestFactory,
    *,
    max_attempts: int = 3,
    base_delay_s: float = 0.2,
    counter: ClientErrorCounter | None = None,
    jitter: bool = True,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> httpx.Response:
    """按 wire-protocol §C 策略执行 ``request_factory``。

    返回成功的 ``httpx.Response``(2xx **或** 409)。409 同样暴露给调用方,
    其响应体带有语义信息(例如 ``code=ALREADY_CLAIMED``)。

    Raises:
        AuthError: 首次 401 / 403,不重试。
        PersistentClientError: 4xx(401/403/409 除外)—— 单次尝试;若累计
            计数器超过阈值,会通过 counter 标记 ``fatal=True``。
        TransientError: 5xx / 传输层错误经过 ``max_attempts`` 仍未成功。
    """
    counter = counter if counter is not None else ClientErrorCounter()
    last_exc: BaseException | None = None
    last_status: int | None = None
    last_code: str | None = None
    last_message: str | None = None
    last_request_id: str | None = None

    for attempt in range(1, max_attempts + 1):
        try:
            resp = await request_factory()
        except (httpx.TransportError, httpx.TimeoutException) as exc:
            last_exc = exc
            last_status = None
            last_code = None
            last_message = str(exc) or exc.__class__.__name__
            last_request_id = None
            if attempt >= max_attempts:
                break
            await _backoff(sleep, base_delay_s, attempt, jitter)
            continue

        status = resp.status_code

        if 200 <= status < 300:
            counter.reset()
            return resp

        # 只解析一次错误信封
        try:
            body = resp.json()
        except (ValueError, httpx.DecodingError):
            body = None
        code, message, request_id = parse_error_body(body)

        if status in (401, 403):
            raise AuthError(
                message or f"HTTP {status} auth failure",
                status_code=status,
                code=code,
                request_id=request_id,
            )

        if status == 409:
            # 视为幂等成功 —— body 由调用方处理。计数器清零(说明已经
            # 顺利到达平台并拿到确定性答复)。
            counter.reset()
            return resp

        if 400 <= status < 500:
            # 404 按 wire-protocol §B 的"log warn, give up"处理 —— 单次
            # 尝试,以 PersistentClientError 形式暴露,但 **不** 污染计数
            # 器(被运维清理后留下的过期 workerCode 在下次 register tick
            # 自然恢复)。
            attempts_after = counter.count
            if status != 404:
                attempts_after = counter.record_client_error()
            raise PersistentClientError(
                message or f"HTTP {status} client error",
                status_code=status,
                code=code,
                request_id=request_id,
                attempts=attempts_after,
            )

        # 5xx → 带退避的重试
        last_status = status
        last_code = code
        last_message = message
        last_request_id = request_id
        last_exc = None
        if attempt >= max_attempts:
            break
        await _backoff(sleep, base_delay_s, attempt, jitter)

    # 重试次数耗尽 —— 抛 TransientError
    raise TransientError(
        last_message or (f"HTTP {last_status} server error" if last_status else "transport error"),
        status_code=last_status,
        code=last_code,
        request_id=last_request_id,
        attempts=max_attempts,
        last_error=last_exc,
    )


async def _backoff(
    sleep: Callable[[float], Awaitable[None]],
    base_delay_s: float,
    attempt: int,
    jitter: bool,
) -> None:
    """睡眠 ``base * 2^(attempt-1)`` 秒,可选 ±10% 抖动。"""
    delay = base_delay_s * (2 ** (attempt - 1))
    if jitter:
        delay *= 1.0 + (random.random() - 0.5) * 0.2
    await sleep(delay)
