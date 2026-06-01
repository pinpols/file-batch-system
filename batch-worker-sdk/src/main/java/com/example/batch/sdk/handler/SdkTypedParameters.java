package com.example.batch.sdk.handler;

import com.example.batch.sdk.task.SdkTaskContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;

/**
 * A.2 — typed handler 共用的「参数反序列化」工具,把 {@link com.example.batch.sdk.task.SdkTypedTaskHandler} 里的
 * Jackson 解析逻辑(resolveInputType / convertValue / toOutputMap)抽出来给 ADR-036 行流模板基类复用, 避免在 4 个 typed
 * shape 基类里重复造轮子。
 *
 * <p>设计取舍:不破坏 {@code SdkTypedTaskHandler} 现有 public API(它仍是独立的「单方法 typed」入口), 这里走「组合」—— typed
 * 行流基类持有一个 {@code SdkTypedParameters<I>} 实例完成入参反序列化, 再走各自模板的行流/分批钩子。
 *
 * <p>泛型类型解析:构造时传入「持有泛型超类的具体子类」与「定义泛型变量的基类」+ 变量下标,从中解析 {@code I} 的 Jackson {@link
 * JavaType};裸用泛型无法解析时退化为 {@link Object}(与 SdkTypedTaskHandler 一致)。
 *
 * <p>包级可见 —— 仅供 handler 包内 typed 基类使用,不对租户暴露。
 *
 * @param <I> 入参类型
 */
final class SdkTypedParameters<I> {

  private final ObjectMapper objectMapper;
  private final JavaType inputType;

  private SdkTypedParameters(ObjectMapper objectMapper, JavaType inputType) {
    this.objectMapper = objectMapper;
    this.inputType = inputType;
  }

  /** 默认 mapper —— 对齐 SDK 其它组件(JavaTimeModule 支持 LocalDate 等时间类型)。 */
  static ObjectMapper defaultObjectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }

  /**
   * 为 {@code concreteHandler} 解析其声明在 {@code declaringBase} 上第 {@code index} 个泛型变量,构造解析器。
   *
   * @param objectMapper 用于类型解析 + 反序列化的 mapper
   * @param concreteHandler 租户的具体 handler(持有泛型实参)
   * @param declaringBase 定义泛型变量的基类(如 {@code SdkAbstractTypedImportHandler.class})
   * @param index 入参泛型变量下标(通常为 0 → {@code I})
   */
  static <I> SdkTypedParameters<I> forHandler(
      ObjectMapper objectMapper, Object concreteHandler, Class<?> declaringBase, int index) {
    JavaType inputType = resolveTypeParameter(objectMapper, concreteHandler, declaringBase, index);
    return new SdkTypedParameters<>(objectMapper, inputType);
  }

  /** 从泛型超类解析指定下标的 Jackson 类型;无法解析(裸用泛型)→ 退化为 Object。 */
  private static JavaType resolveTypeParameter(
      ObjectMapper mapper, Object concreteHandler, Class<?> declaringBase, int index) {
    JavaType self = mapper.getTypeFactory().constructType(concreteHandler.getClass());
    JavaType[] params = mapper.getTypeFactory().findTypeParameters(self, declaringBase);
    if (params == null || params.length <= index) {
      return mapper.getTypeFactory().constructType(Object.class);
    }
    return params[index];
  }

  /**
   * 把 {@link SdkTaskContext#parameters()} 反序列化成强类型 {@code I}。
   *
   * @throws IllegalArgumentException 参数结构不匹配(由调用方转成 fail,不进业务)
   */
  I parse(SdkTaskContext ctx) {
    return objectMapper.convertValue(ctx.parameters(), inputType);
  }

  /** 把业务结果序列化为 output map(null → 空 map)。 */
  Map<String, Object> toOutputMap(Object output) {
    if (output == null) {
      return Map.of();
    }
    JavaType mapType =
        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
    return objectMapper.convertValue(output, mapType);
  }
}
