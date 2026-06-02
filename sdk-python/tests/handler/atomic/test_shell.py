"""Unit tests for :mod:`batch_worker_sdk.handler.atomic._shell`."""

from __future__ import annotations

import sys

import pytest

from batch_worker_sdk.handler.atomic import ShellAtomicConfig, ShellAtomicHandler
from batch_worker_sdk.testkit import make_test_context

pytestmark = pytest.mark.asyncio


async def test_shell_happy_path_captures_stdout() -> None:
    config = ShellAtomicConfig(task_type="shell")
    handler = ShellAtomicHandler(config)
    ctx = make_test_context(parameters={"command": sys.executable, "args": ["-c", "print('hi')"]})

    result = await handler._do_invoke(ctx)

    assert result["exitCode"] == 0
    assert result["stdout"].strip() == "hi"
    assert result["stderr"] == ""
    assert result["stdoutTruncated"] is False


async def test_shell_truncates_oversized_output() -> None:
    config = ShellAtomicConfig(task_type="shell", max_output_bytes=4)
    handler = ShellAtomicHandler(config)
    ctx = make_test_context(
        parameters={
            "command": sys.executable,
            "args": ["-c", "import sys; sys.stdout.write('abcdefghij')"],
        }
    )

    result = await handler._do_invoke(ctx)

    assert result["stdoutTruncated"] is True
    assert result["stdout"] == "abcd"


async def test_shell_non_zero_exit_is_success_not_failure() -> None:
    config = ShellAtomicConfig(task_type="shell")
    handler = ShellAtomicHandler(config)
    ctx = make_test_context(
        parameters={"command": sys.executable, "args": ["-c", "import sys; sys.exit(7)"]}
    )

    # ``_do_invoke`` returns normally; non-zero exit is data, not a thrown error.
    result = await handler._do_invoke(ctx)
    assert result["exitCode"] == 7


async def test_shell_allow_list_rejects_unknown_command() -> None:
    config = ShellAtomicConfig(task_type="shell", allowed_commands=frozenset({"/usr/bin/true"}))
    handler = ShellAtomicHandler(config)
    ctx = make_test_context(parameters={"command": "/bin/echo", "args": ["x"]})

    with pytest.raises(PermissionError, match="not in allowed_commands"):
        await handler._do_invoke(ctx)


async def test_shell_timeout_kills_process_and_raises() -> None:
    config = ShellAtomicConfig(task_type="shell", timeout_seconds=1)
    handler = ShellAtomicHandler(config)
    ctx = make_test_context(
        parameters={
            "command": sys.executable,
            "args": ["-c", "import time; time.sleep(30)"],
        }
    )

    with pytest.raises(TimeoutError, match="timeout"):
        await handler._do_invoke(ctx)


async def test_shell_missing_command_raises() -> None:
    handler = ShellAtomicHandler(ShellAtomicConfig(task_type="shell"))
    ctx = make_test_context(parameters={})
    with pytest.raises(ValueError, match="parameter 'command' is required"):
        await handler._do_invoke(ctx)
