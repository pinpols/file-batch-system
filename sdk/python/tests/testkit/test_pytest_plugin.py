"""验证可选 pytest 插件提供自动启停的 ``fake_platform`` fixture。

``fake_platform`` 由 :mod:`batch_worker_sdk.testkit.pytest_plugin` 提供,经根
``conftest.py`` 的 ``pytest_plugins`` 加载 —— 这正是租户的 opt-in 方式。
"""

from __future__ import annotations

from batch_worker_sdk.testkit import FakeBatchPlatform


async def test_fake_platform_fixture_is_started(
    fake_platform: FakeBatchPlatform,
) -> None:
    assert isinstance(fake_platform, FakeBatchPlatform)
    # 已 start():base_url 可读(未 start 会抛 RuntimeError)。
    assert fake_platform.base_url.startswith("http://127.0.0.1:")


async def test_fake_platform_fixture_is_fresh_per_test(
    fake_platform: FakeBatchPlatform,
) -> None:
    # 每个用例拿到独立实例 → 无前一个用例残留的 report。
    assert fake_platform.get_reports() == []
