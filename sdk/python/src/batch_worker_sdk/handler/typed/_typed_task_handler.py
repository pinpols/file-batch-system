"""单方法 typed task handler —— 对齐 Java
``io.github.pinpols.batch.sdk.handler.typed.SdkTypedTaskHandler``。

Java:``abstract class SdkTypedTaskHandler<I, O>`` —— 租户返回业务对象
``O``,框架将其序列化到 output map。
Python:``Generic[InputT, OutputT]``,两端都是 pydantic 模型。
``OutputT`` 可以是 ``None``(租户返回 ``None`` -> 空 output,
对齐 Java 的 ``null -> Map.of()``)。
"""

from __future__ import annotations

from typing import get_args, get_origin

from pydantic import BaseModel

from batch_worker_sdk.handler.handler import SdkTaskHandler
from batch_worker_sdk.handler.typed._typed_parameters import SdkTypedParameters
from batch_worker_sdk.task.context import SdkTaskContext
from batch_worker_sdk.task.descriptor import SdkTaskTypeDescriptor
from batch_worker_sdk.task.result import SdkTaskResult


def _resolve_input_model(cls: type, generic_base: type, index: int) -> type[BaseModel]:
    """走 ``__orig_bases__`` 找出 ``generic_base`` 上闭合的具体 ``InputT``。

    对齐 Java 的 ``TypeFactory.findTypeParameters(self, declaringBase)``。
    解析失败直接抛错 —— Python typed handler **必须**把 ``InputT`` 闭合到
    pydantic 模型上(没有 Java 那种"裸 Object"回退,因为 pydantic 需要具体
    类才能校验)。
    """
    for klass in cls.__mro__:
        for base in getattr(klass, "__orig_bases__", ()):
            origin = get_origin(base)
            if origin is generic_base or (origin is None and base is generic_base):
                args = get_args(base)
                if args and len(args) > index and isinstance(args[index], type):
                    candidate = args[index]
                    if issubclass(candidate, BaseModel):
                        return candidate  # type: ignore[no-any-return]
    raise TypeError(
        f"{cls.__name__} must close the first generic parameter of "
        f"{generic_base.__name__} on a pydantic BaseModel subclass; "
        f"got {cls.__mro__[1:3]}"
    )


class SdkTypedTaskHandler[InputT: BaseModel, OutputT: BaseModel]:
    """泛型 typed handler 基类 —— 通过 pydantic 强类型 in/out。

    子类**必须**在类声明处把 ``InputT`` 闭合到具体 ``BaseModel`` 子类上,
    框架才能反序列化 ``ctx.parameters`` 而不依赖反射猜测。

    结构性实现 :class:`SdkTaskHandler` Protocol(无需 ``isinstance`` 继承,
    得益于 ``@runtime_checkable``)。
    """

    # 子类可以在类级覆盖,不必走构造器。
    _input_model: type[BaseModel] | None = None

    def __init_subclass__(cls, **kwargs: object) -> None:
        super().__init_subclass__(**kwargs)
        # 跳过中间抽象基类(如 SdkAbstractTypedImportHandler);只在子类把
        # 泛型闭合到具体模型时才解析一次。
        if cls._input_model is not None:
            return
        try:
            cls._input_model = _resolve_input_model(cls, SdkTypedTaskHandler, 0)
        except TypeError:
            # 子类仍是抽象 —— 推迟;具体子类会解析。
            cls._input_model = None

    def task_type(self) -> str:
        raise NotImplementedError

    def descriptor(self) -> SdkTaskTypeDescriptor | None:
        return None

    def cancel(self, ctx: SdkTaskContext) -> None:
        return None

    async def execute(self, ctx: SdkTaskContext) -> SdkTaskResult:
        model = self._input_model
        if model is None:
            return SdkTaskResult.fail(
                "TYPED_PARAMS_UNRESOLVED",
                f"{type(self).__name__}: input pydantic model could not be resolved "
                f"from generic parameters; close InputT on a concrete BaseModel.",
            )
        try:
            params = SdkTypedParameters.parse(ctx.parameters, model)
        except ValueError as ex:
            return SdkTaskResult.fail(
                "INVALID_TYPED_PARAMS",
                f"invalid parameters for taskType={self.task_type()}: {ex}",
                cause=ex,
            )
        result = await self._do_typed_execute(ctx, params)  # type: ignore[arg-type]
        output_map = SdkTypedParameters.serialize(result)
        return SdkTaskResult.success_with(output=output_map, message=self._success_message(result))

    async def _do_typed_execute(self, ctx: SdkTaskContext, params: InputT) -> OutputT | None:
        """租户覆写点 —— 强类型业务逻辑。"""
        raise NotImplementedError

    def _success_message(self, output: OutputT | None) -> str:
        return "ok"


# import 时的健全性检查 —— Protocol 对等性。这里在运行时做(不放模块顶层
# 写 `assert isinstance(..., SdkTaskHandler)`),因为类本身不可实例化;
# 我们只确认方法存在。
assert hasattr(SdkTypedTaskHandler, "task_type")
assert hasattr(SdkTypedTaskHandler, "execute")
# Protocol 结构性检查发生在实例时;类本身无需。
_ = SdkTaskHandler  # re-export 提示
