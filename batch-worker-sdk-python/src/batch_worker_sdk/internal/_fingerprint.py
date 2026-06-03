"""Worker 运行指纹采集 —— 对齐 Java ``WorkerFingerprint``。

register / heartbeat body 中的 ``hostName`` / ``hostIp`` / ``processId`` 等
"看哪台机器在跑" 元数据,SDK 启动期采集一次后整个进程生命周期复用。

字段来源:

- ``host_name``:``socket.gethostname()``;失败回退 ``HOSTNAME`` env / None。
- ``host_ip``:``socket.gethostbyname(host_name)``;失败为 None。
- ``process_id``:``os.getpid()`` 转字符串(与 Java ``ProcessHandle.pid()`` 字符串化对齐)。

所有字段 "尽力而为":任一采集失败均降级为 None,绝不让指纹采集阻断 worker
启动。模块级缓存(``functools.lru_cache``)保证 import 后只采一次。
"""

from __future__ import annotations

import os
import socket
from functools import lru_cache


@lru_cache(maxsize=1)
def host_name() -> str | None:
    """启动期 hostname,与 Java ``WorkerFingerprint.hostName()`` 对齐。"""
    try:
        name = socket.gethostname()
        if name and name.strip():
            return name
    except OSError:
        pass
    env = os.environ.get("HOSTNAME")
    return env.strip() if env and env.strip() else None


@lru_cache(maxsize=1)
def host_ip() -> str | None:
    """启动期解析 hostname → IPv4;失败为 None(平台列可空)。"""
    name = host_name()
    if name is None:
        return None
    try:
        return socket.gethostbyname(name)
    except OSError:
        return None


@lru_cache(maxsize=1)
def process_id() -> str:
    """OS PID 字符串,与 Java ``Long.toString(ProcessHandle.current().pid())`` 对齐。"""
    return str(os.getpid())
