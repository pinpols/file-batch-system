"""SpEL-free 幂等键解析(对齐 Java ``IdempotencyKeyResolver``)。

把 key 模板里的 ``{field}`` 占位符按上下文求值。解析顺序:

1. ``{tenant_id}`` / ``{task_id}`` / ``{task_type}`` → :class:`SdkTaskContext` 字段;
2. 其余 ``{x}`` → ``ctx.parameters.get("x")``。

(同时接受 camelCase 别名 ``{tenantId}`` / ``{taskId}`` / ``{taskType}``,
便于 Java ↔ Python 模板字面量复用。)

占位符求不到值 → 抛 :class:`ValueError`(业务配置错)。不引第三方表达式引擎,
仅正则替换,符合 SDK 轻量约束。
"""

from __future__ import annotations

import re
from typing import Any

from batch_worker_sdk.task.context import SdkTaskContext

_PLACEHOLDER = re.compile(r"\{([a-zA-Z0-9_.]+)\}")

# 上下文字段别名 → ctx 属性名(snake + camel 都收)。
_CONTEXT_FIELDS: dict[str, str] = {
    "tenant_id": "tenant_id",
    "tenantId": "tenant_id",
    "task_id": "task_id",
    "taskId": "task_id",
    "task_type": "task_type",
    "taskType": "task_type",
    "worker_code": "worker_code",
    "workerCode": "worker_code",
}


def resolve_key(template: str, ctx: SdkTaskContext) -> str:
    """解析 key 模板;占位符求不到值时抛 :class:`ValueError`。"""

    def _lookup(field: str) -> Any:
        attr = _CONTEXT_FIELDS.get(field)
        if attr is not None:
            return getattr(ctx, attr)
        return ctx.parameters.get(field)

    def _replace(match: re.Match[str]) -> str:
        field = match.group(1)
        value = _lookup(field)
        if value is None:
            raise ValueError(f"idempotent key placeholder {{{field}}} resolved to null")
        return str(value)

    return _PLACEHOLDER.sub(_replace, template)


__all__ = ["resolve_key"]
