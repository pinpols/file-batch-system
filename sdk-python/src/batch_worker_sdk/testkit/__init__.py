"""batch-worker-sdk testkit — async fake platform + fixtures.

Mirrors the Java :mod:`batch-worker-sdk-testkit` module:

================================================  =====================================
Java                                              Python
================================================  =====================================
``FakeBatchPlatform`` (embedded Kafka + HTTP)     :class:`FakeBatchPlatform` (aiohttp + asyncio.Queue)
``@BatchWorkerTest`` JUnit5 extension             :func:`fake_platform` pytest fixture
``RecordedReport``                                ``dict`` (see :meth:`FakeBatchPlatform.get_reports`)
``TaskDispatchMessageBuilder``                    :func:`make_test_context` + plain dict
================================================  =====================================

Python testkit deliberately stays Kafka-free: real ``aiokafka`` would
drag a broker into every unit test. Instead :meth:`dispatch_task`
pushes onto an ``asyncio.Queue`` that Lane S's Kafka consumer can be
swapped onto in P5.x follow-up. For pure HTTP-layer behaviour
(register / claim / report / heartbeat directive / renew cancel)
the queue is irrelevant — the fake's HTTP endpoints already cover it.

This subpackage requires the optional ``testkit`` extra
(``pip install batch-worker-sdk[testkit]``) so that ``aiohttp`` doesn't
pollute the runtime dependencies of production tenant workers.
"""

from __future__ import annotations

from batch_worker_sdk.testkit.fake_platform import FakeBatchPlatform
from batch_worker_sdk.testkit.fixtures import (
    RecordingHandler,
    make_test_config,
    make_test_context,
)

__all__: list[str] = [
    "FakeBatchPlatform",
    "RecordingHandler",
    "make_test_config",
    "make_test_context",
]
