"""Worker 运行时状态机(NORMAL/DEGRADED/PAUSED/DRAINING)。

对齐 Java ``io.github.pinpols.batch.sdk.dispatcher.WorkerRuntimeState``
(见 ``batch-worker-sdk/src/main/java/io/github/pinpols/batch/sdk/dispatcher/WorkerRuntimeState.java``)。
Phase 2 §2.4:状态机由心跳响应里返回的平台指令驱动。真正根据心跳变更
状态的实现在 P2 落地;本模块只声明枚举,保持公开 API 稳定。
"""

from __future__ import annotations

from enum import StrEnum


class WorkerRuntimeState(StrEnum):
    """Worker 可处于的运行时状态(字符串值以便 wire 等价)。"""

    NORMAL = "NORMAL"
    """正常:接收派发,按常规执行任务。"""

    DEGRADED = "DEGRADED"
    """降级:平台提示降低并发;仍接收派发。"""

    PAUSED = "PAUSED"
    """暂停:不再认领新任务(可恢复);在执行任务自然 drain。"""

    DRAINING = "DRAINING"
    """排空:不再认领新任务(终态);通常先于关停。"""

    def accepts_new_tasks(self) -> bool:
        """当前状态是否接受新任务 claim。

        ``PAUSED`` / ``DRAINING`` 拒绝;``NORMAL`` / ``DEGRADED`` 接受。
        对齐 Java ``acceptsNewTasks()``。
        """
        return self in (WorkerRuntimeState.NORMAL, WorkerRuntimeState.DEGRADED)
