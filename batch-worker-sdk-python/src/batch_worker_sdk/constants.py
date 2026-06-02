"""SDK 共享常量 —— 与 ``docs/api/sdk-shared-constants.yaml`` 1:1 镜像。

权威顺序(详见 yaml 文件头注释):

1. Java 端枚举 / static-final 列表(Java ``SharedConstantsParityTest`` 锁定)
2. ``docs/api/sdk-shared-constants.yaml``(由 Java 镜像产出)
3. 本模块及其他语言 SDK(消费 yaml,**不可**自行编值)

本模块由 ``tests/test_shared_constants_parity.py`` 做 strict 双向集合等价校验
(yaml ↔ Python set),任何一侧漂移测试立刻 fail。

新增/修改常量的正确流程:

1. Java 改枚举,Java parity test 通过
2. 改 yaml 镜像
3. 改本模块
4. ``pytest sdk-python/tests/test_shared_constants_parity.py`` 通过

刻意 **不** 在 ``__init__.py`` 重导出为 ``WorkerRuntimeState`` 同名:运行态枚举
已在 :mod:`batch_worker_sdk.task.state`;这里只暴露字符串集合便于 yaml parity 与
跨语言协议字段校验使用。
"""

from __future__ import annotations

from typing import Final

# 对齐 Java ``TaskDispatchMessage.SUPPORTED_MAJOR_VERSIONS``。
SCHEMA_VERSIONS_SUPPORTED: Final[tuple[str, ...]] = ("v1", "v2")

# 对齐 Java ``WorkerRuntimeState`` enum;运行态实现见
# :mod:`batch_worker_sdk.task.state`,这里仅作为 yaml parity 用字符串集合。
WORKER_RUNTIME_STATES: Final[frozenset[str]] = frozenset(
    {"NORMAL", "DEGRADED", "PAUSED", "DRAINING"}
)

# 对齐 Java ``SensitiveDataValidator.SENSITIVE_KEYWORDS``(13 项)。
SENSITIVE_KEYWORDS: Final[frozenset[str]] = frozenset(
    {
        "password",
        "passwd",
        "secret",
        "apikey",
        "api_key",
        "token",
        "credential",
        "accesskey",
        "access_key",
        "privatekey",
        "private_key",
        "clientsecret",
        "client_secret",
    }
)

# 对齐 Java ``TaskStatus`` 枚举 / ``job_task.task_status`` CHECK 约束。
TASK_STATUSES: Final[tuple[str, ...]] = (
    "CREATED",
    "READY",
    "RUNNING",
    "SUCCESS",
    "FAILED",
    "CANCELLED",
    "TERMINATED",
)

__all__ = [
    "SCHEMA_VERSIONS_SUPPORTED",
    "SENSITIVE_KEYWORDS",
    "TASK_STATUSES",
    "WORKER_RUNTIME_STATES",
]
