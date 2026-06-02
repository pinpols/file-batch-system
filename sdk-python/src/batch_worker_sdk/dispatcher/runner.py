"""Provisional run-loop wiring HTTP + Dispatcher + Kafka consumer together.

# Provisional API — Will be superseded by BatchPlatformClient in P3.

This module exists so P2 testkit consumers (and the contract runner)
have something concrete to spin up. Once P3 lands ``BatchPlatformClient``
(register / heartbeat / lease-renewal scheduler + lifecycle), callers
should migrate to it; ``run_worker`` will then become a thin shim
calling ``BatchPlatformClient.run_forever()``.
"""

from __future__ import annotations

import asyncio
import logging

from batch_worker_sdk.client.config import BatchPlatformClientConfig
from batch_worker_sdk.dispatcher.dispatcher import TaskDispatcher
from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.internal._http import PlatformHttpClient
from batch_worker_sdk.internal._kafka import KafkaTaskConsumer

logger = logging.getLogger(__name__)


async def run_worker(
    config: BatchPlatformClientConfig,
    handlers: dict[str, SdkTaskHandler] | None = None,
    *,
    shutdown_timeout: float = 30.0,
) -> None:
    """Run the SDK until cancelled / KeyboardInterrupt.

    Provisional P2 entrypoint composing the three building blocks:
    ``PlatformHttpClient`` (HTTP) + ``TaskDispatcher`` (CLAIM→REPORT) +
    ``KafkaTaskConsumer`` (dispatch ingest). No register / heartbeat
    loop yet — that lands in P3.

    Args:
        config: Validated config including ``kafka_*`` fields.
        handlers: Map of ``workerType → SdkTaskHandler``. May be empty
            for smoke tests; missing handlers cause REPORT failure.
        shutdown_timeout: Seconds to wait for in-flight drain on
            ``CancelledError`` before forcing close.
    """
    http = PlatformHttpClient(config)
    dispatcher = TaskDispatcher(config, http, handlers=handlers)
    consumer = KafkaTaskConsumer(config, dispatcher)
    try:
        await consumer.start()
        # Block forever; outer task cancellation triggers shutdown.
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            logger.info("run_worker cancelled, shutting down")
            raise
    finally:
        await consumer.stop()
        await dispatcher.shutdown(timeout=shutdown_timeout)
        await http.close()
