"""batch-worker-sdk(Python)的异常体系。

wire-protocol §B 将 HTTP 失败划分为四种类型,SDK 必须区分。这里以具体异
常子类建模该分类,调用方可以 ``except AuthError:`` / ``except TransientError:``
而不是手工判断 status code。

Java 对应:``com.example.batch.sdk.internal.PlatformHttpException`` 及其
``isAuthError() / isConflict() / isServerError()`` 谓词 —— Python 端把这
些谓词折叠成类层次。

所有异常都暴露同一组诊断字段:

- ``status_code``  : HTTP 状态码(传输层错误时为 ``None``)
- ``code``         : 后端 BizException 的 i18n key(如 ``AUTH_INVALID``)
- ``message``      : 人类可读的描述
- ``request_id``   : 后端 error body 中的 ``traceId``(若存在)
"""

from __future__ import annotations

from typing import Any


class PlatformError(Exception):
    """SDK 抛出的所有协议层错误的基类。

    通用的 ``except PlatformError:`` 能捕获所有已分类失败,同时不影响真
    正意料之外的 ``Exception``(编程错误、``KeyboardInterrupt`` 等)继续
    向上传播。
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        code: str | None = None,
        request_id: str | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.request_id = request_id

    def __repr__(self) -> str:  # pragma: no cover - trivial
        return (
            f"{self.__class__.__name__}(status_code={self.status_code!r}, "
            f"code={self.code!r}, request_id={self.request_id!r}, "
            f"message={self.message!r})"
        )


class AuthError(PlatformError):
    """401 / 403 —— 凭据无效或租户作用域越界。

    按 wire-protocol §B 必须 fail-fast:不重试,并把 dispatcher 设为 fatal。
    重试辅助在首次命中时直接抛出此异常。
    """


class ConflictError(PlatformError):
    """409 —— 幂等结果已应用(如任务已被 claim 等)。

    按 §B 视为成功:调用方仅 INFO 记录后继续。我们仍以异常形式提供,便于
    调用方在确实需要响应体时主动 branch;重试辅助 **不会** 抛出此异常 ——
    它把 409 转成正常返回。本类型保留给调用方显式获取响应体的场景。
    """


class PersistentClientError(PlatformError):
    """4xx(401/403/404/409 除外)累计超过 fail-fast 阈值(默认 5)。

    通常意味着 SDK ↔ 平台契约漂移,重试也无济于事。
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        code: str | None = None,
        request_id: str | None = None,
        attempts: int = 0,
    ) -> None:
        super().__init__(message, status_code=status_code, code=code, request_id=request_id)
        self.attempts = attempts


class TransientError(PlatformError):
    """5xx / 传输层错误,且指数退避预算已耗尽
    (默认 ``max_attempts=3``:200ms / 400ms / 800ms)。

    调用方应仅 log 并丢弃;下次心跳 / 租约 tick 或下次用户操作会自然
    重试。
    """

    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        code: str | None = None,
        request_id: str | None = None,
        attempts: int = 0,
        last_error: BaseException | None = None,
    ) -> None:
        super().__init__(message, status_code=status_code, code=code, request_id=request_id)
        self.attempts = attempts
        self.last_error = last_error


class SdkTaskStopped(Exception):
    """协作式取消的安全点信号(ADR-037 决策三,P3)。

    每次 :meth:`SdkTaskContext.commit` 成功后,若 ``ctx.is_cancelled()`` 命中,
    则在**已提交的安全点**抛此异常 —— 取消总是停在两个批次之间的边界,不会留
    半个批次的异常数据。续跑模板的顶层 ``execute`` 捕获它 → 落 cancelled 终态。

    红线:**业务代码不得捕获并抑制本异常**(吞了就停不下来)。它刻意不继承
    :class:`PlatformError`,以免被业务的 ``except PlatformError`` 误捕。

    :param break_position: 取消发生时已安全提交到的断点(已落盘)。
    """

    def __init__(self, break_position: dict[str, Any] | None = None) -> None:
        self.break_position: dict[str, Any] = dict(break_position or {})
        super().__init__(f"task stopped at safe-point break_position={self.break_position}")


def parse_error_body(body: Any) -> tuple[str | None, str | None, str | None]:
    """从后端 error 信封里 best-effort 提取 ``(code, message, trace_id)``。

    后端 BizException 渲染为 ``{"code": "...", "message": "...",
    "traceId": "..."}``;较老的路径用 ``trace_id``。body 不是 dict 时返回
    ``(None, None, None)``。
    """
    if not isinstance(body, dict):
        return None, None, None
    code = body.get("code")
    message = body.get("message")
    trace_id = body.get("traceId") or body.get("trace_id") or body.get("requestId")
    return (
        code if isinstance(code, str) else None,
        message if isinstance(message, str) else None,
        trace_id if isinstance(trace_id, str) else None,
    )
