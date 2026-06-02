"""Sample Python tenant worker entry point.

Demonstrates ADR-035 tenant-self-hosted worker integration:

1. Use ``@batch_task`` to declare handlers.
2. Build ``BatchPlatformClientConfig`` from env.
3. ``collect_registered_handlers()`` pulls every decorated handler.
4. (P3 / Lane T) ``BatchPlatformClient`` will host the run loop —
   this entry point is forward-compatible: it imports lazily and
   degrades gracefully if Lane T hasn't merged yet.
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
    """Echo the input parameters back as the result output."""
    return SdkTaskResult.success_with(
        {"echo": dict(ctx.parameters)},
        f"echoed taskId={ctx.task_id}",
    )


@batch_task("sample-sleep")
async def sleep(ctx: SdkTaskContext) -> SdkTaskResult:
    """Sleep ``parameters.millis`` ms then return."""
    millis = int(ctx.parameters.get("millis", 100))
    await asyncio.sleep(millis / 1000.0)
    return SdkTaskResult.success_with({"slept": millis})


async def main() -> None:
    """Wire handlers into the platform client and run until shutdown.

    Lane T owns ``BatchPlatformClient``. Until it lands the entry point
    exits early after logging the registered handlers — the example is
    still useful as a Smoke-test of the decorator + config wiring.
    """
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    cfg = BatchPlatformClientConfig.from_env()
    handlers = collect_registered_handlers()
    logger.info(
        "registered %d handler(s): %s", len(handlers), [h.task_type() for h in handlers]
    )

    try:
        from batch_worker_sdk.client import BatchPlatformClient  # type: ignore[attr-defined]
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
        await asyncio.Event().wait()  # block until SIGINT
    finally:
        await client.stop(timeout=30)
