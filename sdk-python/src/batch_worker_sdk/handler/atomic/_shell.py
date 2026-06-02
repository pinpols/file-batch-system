"""Shell atomic handler — async port of Java ``ShellAtomicHandler``.

Security design (matches Java SDK / ADR-029 dual-use RCE isolation):

* **No shell interpreter** — uses ``asyncio.create_subprocess_exec``
  (which underlies ``execve``); the ``;`` ``|`` ``&&`` in ``args`` are
  literal program arguments, never shell-interpreted.
* **Command allow-list** — when non-empty, ``command`` must be an exact
  match (absolute path).
* **Timeout kills** — over the budget, ``Process.kill()`` is called.
* **Workdir isolation** — fresh tempdir per invocation, cleaned up
  after when ``cleanup_workdir`` is true.
* **Output truncation** — stdout / stderr each capped at
  ``max_output_bytes``.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import shutil
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from batch_worker_sdk.handler._atomic import SdkAbstractAtomicHandler
from batch_worker_sdk.task.context import SdkTaskContext

_LOG = logging.getLogger(__name__)


@dataclass(frozen=True)
class ShellAtomicConfig:
    """Config for :class:`ShellAtomicHandler` (mirrors Java ``ShellAtomicConfig``).

    Attributes:
        task_type: Registered task-type code.
        allowed_commands: Command allow-list (exact absolute path match).
            Empty = dev-only full allow. **Production MUST set this** —
            otherwise any executable is exposed to the dispatcher (RCE).
        timeout_seconds: Subprocess timeout. On timeout the process is
            killed and the call fails.
        max_output_bytes: Per-stream cap for stdout/stderr.
        cleanup_workdir: Recursively delete the temp workdir on exit.
    """

    task_type: str
    allowed_commands: frozenset[str] = field(default_factory=frozenset)
    timeout_seconds: int = 60
    max_output_bytes: int = 64 * 1024
    cleanup_workdir: bool = True

    def __post_init__(self) -> None:
        if not self.task_type or not self.task_type.strip():
            raise ValueError("task_type must not be blank")
        if self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be > 0")
        if self.max_output_bytes <= 0:
            raise ValueError("max_output_bytes must be > 0")
        object.__setattr__(self, "allowed_commands", frozenset(self.allowed_commands))

    @classmethod
    def defaults(cls, task_type: str) -> ShellAtomicConfig:
        """Defaults: empty allow-list (dev), 60 s timeout, 64 KiB cap, cleanup on."""
        return cls(task_type=task_type)


class ShellAtomicHandler(SdkAbstractAtomicHandler):
    """Out-of-the-box subprocess atomic handler.

    Parameters (from ``ctx.parameters``):

    * ``command`` (str, required) — absolute program path
    * ``args`` (list[str], optional)

    Output dict: ``exitCode`` / ``stdout`` / ``stderr`` /
    ``stdoutTruncated`` / ``stderrTruncated``. A non-zero exit code is
    **not** a handler failure (Java parity).
    """

    def __init__(self, config: ShellAtomicConfig) -> None:
        if config is None:
            raise ValueError("config must not be None")
        self._config = config

    def task_type(self) -> str:
        return self._config.task_type

    async def _do_invoke(self, ctx: SdkTaskContext) -> dict[str, Any]:
        command, args = _read_command_and_args(ctx)

        if self._config.allowed_commands and command not in self._config.allowed_commands:
            raise PermissionError(f"command not in allowed_commands: {command}")

        workdir = Path(tempfile.mkdtemp(prefix="sdk-shell-"))
        try:
            return await self._run_process(command, args, workdir)
        finally:
            if self._config.cleanup_workdir:
                shutil.rmtree(workdir, ignore_errors=True)

    async def _run_process(
        self, command: str, args: list[str], workdir: Path
    ) -> dict[str, Any]:
        try:
            process = await asyncio.create_subprocess_exec(
                command,
                *args,
                cwd=str(workdir),
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except FileNotFoundError as exc:
            raise FileNotFoundError(f"command not found: {command}") from exc

        try:
            stdout_b, stderr_b = await asyncio.wait_for(
                process.communicate(), timeout=self._config.timeout_seconds
            )
        except TimeoutError as exc:
            process.kill()
            with contextlib.suppress(Exception):
                await process.wait()
            raise TimeoutError(
                f"shell command timeout after {self._config.timeout_seconds}s: {command}"
            ) from exc

        stdout_text, stdout_trunc = _truncate(stdout_b, self._config.max_output_bytes)
        stderr_text, stderr_trunc = _truncate(stderr_b, self._config.max_output_bytes)
        return {
            "exitCode": process.returncode if process.returncode is not None else -1,
            "stdout": stdout_text,
            "stderr": stderr_text,
            "stdoutTruncated": stdout_trunc,
            "stderrTruncated": stderr_trunc,
        }


def _read_command_and_args(ctx: SdkTaskContext) -> tuple[str, list[str]]:
    command_raw = ctx.parameters.get("command")
    if not isinstance(command_raw, str) or not command_raw.strip():
        raise ValueError("parameter 'command' is required (absolute program path)")
    args_raw = ctx.parameters.get("args")
    if args_raw is None:
        return command_raw, []
    if not isinstance(args_raw, list):
        raise ValueError("parameter 'args' must be a list[str]")
    return command_raw, [str(a) for a in args_raw]


def _truncate(data: bytes | None, cap: int) -> tuple[str, bool]:
    data = data or b""
    truncated = len(data) > cap
    if truncated:
        data = data[:cap]
    return data.decode("utf-8", errors="replace"), truncated
