"""验证示例进程收到停止信号后执行 SDK 停机流程。"""

import os
import select
import signal
import subprocess
import sys
import time
from pathlib import Path

import pytest


@pytest.mark.skipif(os.name != "posix", reason="示例的进程信号测试依赖 POSIX")
@pytest.mark.parametrize("stop_signal", [signal.SIGTERM, signal.SIGINT])
def test_process_signal_stops_client(stop_signal: signal.Signals) -> None:
    root = Path(__file__).resolve().parents[4]
    sample_source = root / "examples/self-hosted-sdk/sample-tenant-worker-python/src"
    sdk_source = root / "sdk/python/src"
    env = os.environ.copy()
    env["PYTHONPATH"] = os.pathsep.join((str(sample_source), str(sdk_source)))
    child_code = """
import asyncio
import importlib
from types import SimpleNamespace

sample = importlib.import_module('sample_tenant_worker.main')
platform = importlib.import_module('batch_worker_sdk.client.client')
sample.BatchPlatformClientConfig = SimpleNamespace(from_env=lambda: None)

class FakeClient:
    def __init__(self, config):
        pass
    def register_handler(self, handler):
        pass
    async def start(self):
        print('READY', flush=True)
    async def stop(self, timeout):
        print(f'STOPPED:{timeout}', flush=True)

platform.BatchPlatformClient = FakeClient
asyncio.run(sample.main())
"""
    process = subprocess.Popen(
        [sys.executable, "-u", "-c", child_code],
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )
    assert process.stdout is not None
    try:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            ready, _, _ = select.select([process.stdout], [], [], 0.2)
            if ready and "READY" in process.stdout.readline():
                break
            if process.poll() is not None:
                pytest.fail("示例进程在启动完成前退出")
        else:
            pytest.fail("未观察到示例进程启动完成")
        process.send_signal(stop_signal)
        output, _ = process.communicate(timeout=10)
        assert process.returncode == 0, output
        assert "STOPPED:30" in output
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
