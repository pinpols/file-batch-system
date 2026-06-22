"""可选 pytest 插件 —— 自动启停的 ``fake_platform`` fixture。

对标 Java ``@BatchWorkerTest`` JUnit5 extension:每个用例拿到一个**已启动**的
:class:`FakeBatchPlatform`,用例结束自动 ``stop()``,免去每个测试文件各自重写
一份 start/stop fixture 的样板。

租户在 ``conftest.py``(或测试根)里 opt-in::

    pytest_plugins = ["batch_worker_sdk.testkit.pytest_plugin"]

然后直接用 fixture::

    async def test_my_handler(fake_platform):
        client = BatchPlatformClient.builder(
            make_test_config(base_url=fake_platform.base_url)
        ).register(MyHandler()).build()
        ...

刻意单独成模块(**不**在 :mod:`batch_worker_sdk.testkit` 的 ``__init__`` 里
import),这样 ``import batch_worker_sdk.testkit`` 不会把 pytest 拖成依赖。本模块
需 ``batch-worker-sdk[testkit]`` extra(含 pytest / pytest-asyncio);用
``pytest_asyncio.fixture`` 故在 strict 与 auto 两种 asyncio 模式下都可用。
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import pytest_asyncio

from batch_worker_sdk.testkit.fake_platform import FakeBatchPlatform


@pytest_asyncio.fixture
async def fake_platform() -> AsyncIterator[FakeBatchPlatform]:
    """Yield 一个已 ``start()`` 的 :class:`FakeBatchPlatform`,用例后自动 ``stop()``。"""
    platform = FakeBatchPlatform()
    await platform.start()
    try:
        yield platform
    finally:
        await platform.stop()
