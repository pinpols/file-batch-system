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
DRY_RUN_SAFE_CAPABILITY: Final[str] = "dry-run-safe"

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

# 对齐 Java ``SdkErrorCode`` / Go ``protocol.ErrorCode*`` / TS ``ErrorCode`` /
# Rust ``protocol::error_code``:report body ``errorCode`` 的规范协议码(wire-protocol §B)。
# 平台按 errorCode 聚合失败告警,因此 Python 兜底必须发规范码(如 EXECUTION_FAILED)而不是
# 异常类名。注意这些值与 ``TASK_STATUSES`` 里的 SUCCESS / CANCELLED 同名但**不同域**
# (task 生命周期状态 vs 失败分类),故统一加 ``ERROR_CODE_`` 前缀以免误用。
ERROR_CODE_SUCCESS: Final[str] = "SUCCESS"
ERROR_CODE_TIMEOUT: Final[str] = "TIMEOUT"
ERROR_CODE_CANCELLED: Final[str] = "CANCELLED"
ERROR_CODE_KILLED: Final[str] = "KILLED"
ERROR_CODE_SECURITY_REJECTED: Final[str] = "SECURITY_REJECTED"
ERROR_CODE_EXECUTION_FAILED: Final[str] = "EXECUTION_FAILED"
ERROR_CODE_CONFIG_INVALID: Final[str] = "CONFIG_INVALID"
ERROR_CODE_RESOURCE_EXHAUSTED: Final[str] = "RESOURCE_EXHAUSTED"

# yaml ``report_error_codes`` 的镜像(顺序与 Java ``SdkErrorCode`` 声明顺序一致;
# Go/Rust/TS 侧 parity 用深比较,故顺序必须一致)。
REPORT_ERROR_CODES: Final[tuple[str, ...]] = (
    ERROR_CODE_SUCCESS,
    ERROR_CODE_TIMEOUT,
    ERROR_CODE_CANCELLED,
    ERROR_CODE_KILLED,
    ERROR_CODE_SECURITY_REJECTED,
    ERROR_CODE_EXECUTION_FAILED,
    ERROR_CODE_CONFIG_INVALID,
    ERROR_CODE_RESOURCE_EXHAUSTED,
)

__all__ = [
    "DRY_RUN_SAFE_CAPABILITY",
    "ERROR_CODE_CANCELLED",
    "ERROR_CODE_CONFIG_INVALID",
    "ERROR_CODE_EXECUTION_FAILED",
    "ERROR_CODE_KILLED",
    "ERROR_CODE_RESOURCE_EXHAUSTED",
    "ERROR_CODE_SECURITY_REJECTED",
    "ERROR_CODE_SUCCESS",
    "ERROR_CODE_TIMEOUT",
    "REPORT_ERROR_CODES",
    "SCHEMA_VERSIONS_SUPPORTED",
    "SENSITIVE_KEYWORDS",
    "TASK_STATUSES",
    "WORKER_RUNTIME_STATES",
]
