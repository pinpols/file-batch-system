"""batch-worker-sdk testkit —— 异步 fake 平台 + fixtures。

对标 Java :mod:`batch-worker-sdk-testkit` 模块:

================================================  =====================================
Java                                              Python
================================================  =====================================
``FakeBatchPlatform``(内嵌 Kafka + HTTP)         :class:`FakeBatchPlatform`(aiohttp + asyncio.Queue)
``@BatchWorkerTest`` JUnit5 extension             ``fake_platform`` fixture(opt-in :mod:`batch_worker_sdk.testkit.pytest_plugin`)
``RecordedReport``                                ``dict``(见 :meth:`FakeBatchPlatform.get_reports`)
``TaskDispatchMessageBuilder``                    :func:`make_test_context` + 普通 dict
================================================  =====================================

Python testkit 刻意不引入 Kafka:真实 ``aiokafka`` 会把 broker 拖进每个单测。
:meth:`dispatch_task` 改为推到 ``asyncio.Queue``,后续 Lane S(Kafka 消费者)
落地后可平替成真实消费循环。对于纯 HTTP 层行为
(register / claim / report / heartbeat directive / renew cancel),
队列无关紧要 —— fake 的 HTTP 端点已经覆盖。

本子包要求安装可选 ``testkit`` extra
(``pip install batch-worker-sdk[testkit]``),避免 ``aiohttp``
污染生产租户 worker 的运行时依赖。
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
