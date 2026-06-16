"""Shell atomic handler —— Java ``ShellAtomicHandler`` 的异步移植。

安全设计(对齐 Java SDK / ADR-029 dual-use RCE 隔离):

* **不用 shell 解释器** —— 用 ``asyncio.create_subprocess_exec``
  (底层是 ``execve``);``args`` 中的 ``;`` ``|`` ``&&`` 都是字面量
  程序参数,不会被 shell 解释。
* **命令白名单** —— 非空时,``command`` 必须精确匹配(绝对路径)。
* **超时即杀** —— 超过预算后调用 ``Process.kill()``。
* **工作目录隔离** —— 每次调用一个新建临时目录,``cleanup_workdir``
  为 true 时事后清理。
* **输出截断** —— stdout / stderr 各自上限 ``max_output_bytes``。
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
    """:class:`ShellAtomicHandler` 的配置(对齐 Java ``ShellAtomicConfig``)。

    Attributes:
        task_type: 注册的任务类型码。
        allowed_commands: 命令白名单(绝对路径精确匹配)。
            空集 = 仅开发环境全放行。**生产必须显式配置** ——
            否则任何可执行文件都暴露给 dispatcher(RCE)。
        timeout_seconds: 子进程超时。超时即杀进程并失败。
        max_output_bytes: stdout/stderr 每条流的上限。
        cleanup_workdir: 退出时递归删除临时 workdir。
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
        """默认值:空白名单(开发用)、60s 超时、64 KiB 上限、开启清理。"""
        return cls(task_type=task_type)


class ShellAtomicHandler(SdkAbstractAtomicHandler):
    """开箱即用的子进程 atomic handler。

    参数(来自 ``ctx.parameters``):

    * ``command`` (str,必填)—— 绝对程序路径
    * ``args`` (list[str],可选)

    输出 dict:``exitCode`` / ``stdout`` / ``stderr`` /
    ``stdoutTruncated`` / ``stderrTruncated``。非零 exit code **不**
    视为 handler 失败(与 Java 对齐)。
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

    async def _run_process(self, command: str, args: list[str], workdir: Path) -> dict[str, Any]:
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
