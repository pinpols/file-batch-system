"""租户实现的任务处理协议(对齐 Java SdkTaskHandler)。

对齐 Java ``io.github.pinpols.batch.sdk.task.SdkTaskHandler``。Python 形式采用
:class:`~typing.Protocol` 而非 :class:`abc.ABC`:运行时可校验的结构化类型
更契合异步 handler,也省掉了 Java 必须、Python 不需要的继承样板。

Phase 1+ 会再加一个带重试/幂等钩子的抽象基类;当前先把公开形态钉住,
让下游 lane 不出现循环依赖即可引用此类型。
"""

from __future__ import annotations

from typing import Protocol, runtime_checkable

from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult


@runtime_checkable
class SdkTaskHandler(Protocol):
    """租户为每种任务类型实现的协议。

    一个 Python worker 进程可注册多个 handler;SDK 分派循环按
    :meth:`task_type` 路由。

    典型用法(Phase 1+ 待 :class:`WorkerClient` 落地后)::

        class MyImportHandler:
            def task_type(self) -> str:
                return "tenant_xyz_import"

            async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
                rows = await load_rows(ctx.parameters["source"])
                return SdkTaskResult.success_with(
                    output={"rows": len(rows)},
                    message=f"imported {len(rows)} rows",
                )
    """

    def task_type(self) -> str:
        """全局唯一的任务类型码(对应 ``job_definition.job_type``)。"""
        ...

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        """执行任务。``ctx`` 由框架注入,返回结果对象。"""
        ...

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        """可选 —— 声明自定义任务类型描述符,默认 ``None``。

        返回非 None 时会在 worker 注册阶段一并上报;平台据此 upsert
        ``custom_task_type_registry``,控制台再依据内嵌 JSON Schema 渲染表单。
        """
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        """可选 —— 协作式取消钩子,默认空实现。

        平台下发取消信号时由 SDK 调用。多数 handler 不实现此方法,改用
        :attr:`SdkTaskContext.is_dry_run` 风格的轮询;只有需要同步清理时才需重写。
        """
        return None
