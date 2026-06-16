"""Python 租户 worker 示例入口。

演示 ADR-035 租户自托管 worker 集成方式:

1. 用 ``@batch_task`` 声明 handler。
2. 从环境变量构造 ``BatchPlatformClientConfig``。
3. ``collect_registered_handlers()`` 收集全部被装饰过的 handler。
4. (P3 / Lane T)``BatchPlatformClient`` 将托管运行循环 ——
   本入口前向兼容:懒 import,Lane T 未合并时优雅降级。
"""

from __future__ import annotations

import asyncio
import logging

from batch_worker_sdk import (
    BatchPlatformClientConfig,
    SdkTaskContext,
    SdkTaskResult,
    batch_task,
    collect_registered_handlers,
)

logger = logging.getLogger("sample_tenant_worker")


@batch_task("sample-echo")
async def echo(ctx: SdkTaskContext) -> SdkTaskResult:
    """把入参原样回显作为结果输出。"""
    return SdkTaskResult.success_with(
        {"echo": dict(ctx.parameters)},
        f"echoed taskId={ctx.task_id}",
    )


@batch_task("sample-sleep")
async def sleep(ctx: SdkTaskContext) -> SdkTaskResult:
    """睡眠 ``parameters.millis`` 毫秒后返回。"""
    millis = int(ctx.parameters.get("millis", 100))
    await asyncio.sleep(millis / 1000.0)
    return SdkTaskResult.success_with({"slept": millis})


async def main() -> None:
    """把 handler 接入平台 client 并运行到关停。

    ``BatchPlatformClient`` 由 Lane T 负责;落地之前入口在打印
    已注册 handler 后立即退出 —— 仍可用于 decorator + config
    wiring 的 smoke 测试。
    """
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s"
    )
    cfg = BatchPlatformClientConfig.from_env()
    handlers = collect_registered_handlers()
    logger.info(
        "registered %d handler(s): %s", len(handlers), [h.task_type() for h in handlers]
    )

    try:
        from batch_worker_sdk.client.client import BatchPlatformClient  # type: ignore[attr-defined]
    except ImportError:
        logger.warning(
            "BatchPlatformClient (Lane T) not yet available — exiting after registration smoke."
        )
        return

    client = BatchPlatformClient(cfg)
    for h in handlers:
        client.register_handler(h)
    await client.start()
    try:
        await asyncio.Event().wait()  # 阻塞直到 SIGINT
    finally:
        await client.stop(timeout=30)
