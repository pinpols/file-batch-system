"""Typed 参数反序列化助手 —— 对齐 Java
``io.github.pinpols.batch.sdk.handler.typed.SdkTypedParameters``。

Java 用 Jackson + 反射式 ``JavaType.findTypeParameters`` 从具体 handler 子类
解析泛型 ``<I>``。Python 干同样的活 —— 但由 pydantic v2 同时完成 schema 校验
和 Map ↔ model 转换 —— 通过显式 ``model_type`` 参数驱动,该参数在
handler 构造时从 ``typing.Generic`` 参数解析得到(见
:class:`SdkTypedTaskHandler`)。

为什么单独抽 helper 而不内联 ``model.model_validate``:
4 个行流水模板(Import/Export/Process/Dispatch)和单方法 typed handler
共享完全一致的 parse/serialize 语义;Java 把它们组合,Python 跟随 ——
租户拿到的错误文案完全一致("invalid parameters for taskType=…:
<pydantic msg>"),而不是各模板特设的措辞。
"""

from __future__ import annotations

from typing import Any, TypeVar

from pydantic import BaseModel, ValidationError

T = TypeVar("T", bound=BaseModel)


class SdkTypedParameters:
    """Java ``SdkTypedParameters`` 的 pydantic 实现镜像。

    无状态工具类(Java 持每实例 ``ObjectMapper`` + 已解析的 ``JavaType``;
    Python 不需要这些 —— pydantic 的 ``model_validate`` / ``model_dump``
    无需预解析就能完成两端工作)。
    """

    @staticmethod
    def parse(raw: dict[str, Any] | None, model_type: type[T]) -> T:
        """把 ``raw``(通常是 ``ctx.parameters``)反序列化为 ``model_type``。

        校验失败时抛 ``ValueError``,带一条扁平、易读的消息 —— typed 模板捕获
        后转成 :meth:`SdkTaskResult.fail`(输入不合法时业务代码不会跑,
        对齐 Java ``IllegalArgumentException`` 契约)。
        """
        if not issubclass(model_type, BaseModel):
            raise TypeError(
                f"SdkTypedParameters.parse requires a pydantic BaseModel subclass, "
                f"got {model_type!r}"
            )
        payload: dict[str, Any] = raw or {}
        try:
            return model_type.model_validate(payload)
        except ValidationError as e:
            # 紧凑单行,便于干净落地到 REPORT message。
            errs = "; ".join(
                f"{'.'.join(str(p) for p in err['loc']) or '<root>'}: {err['msg']}"
                for err in e.errors()
            )
            raise ValueError(f"parameters do not match {model_type.__name__}: {errs}") from e

    @staticmethod
    def serialize(model: BaseModel | None) -> dict[str, Any]:
        """把 pydantic 模型序列化进 ``output`` map。

        ``None`` -> 空 dict(对齐 Java ``toOutputMap(null) -> Map.of()``)。
        """
        if model is None:
            return {}
        if not isinstance(model, BaseModel):
            raise TypeError(
                f"SdkTypedParameters.serialize expects a pydantic BaseModel, "
                f"got {type(model).__name__}"
            )
        return model.model_dump(mode="json")
