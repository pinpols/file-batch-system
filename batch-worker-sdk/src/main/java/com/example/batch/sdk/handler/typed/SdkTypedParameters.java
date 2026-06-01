package com.example.batch.sdk.handler.typed;

import com.example.batch.sdk.task.SdkTaskContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;

/**
 * SDK typed handler 共用的「参数反序列化」工具 —— 把 {@link SdkTaskContext#parameters()} 经 Jackson 反序列化成强类型入参
 * {@code I},并把业务结果序列化回 output map。
 *
 * <p>单一权威实现:{@link SdkTypedTaskHandler}(单方法 typed 入口)与 {@code handler} 包的 4 个 typed 行流模板基类 都持有一个
 * {@code SdkTypedParameters<I>} 完成入参解析(组合),避免在各处重复造 resolve/convert/toOutput 轮子。
 *
 * <p>泛型类型解析:构造时传入「持有泛型实参的具体子类」与「定义泛型变量的基类」+ 变量下标,从中解析 {@code I} 的 Jackson {@link
 * JavaType};裸用泛型无法解析时退化为 {@link Object}。
 *
 * @param <I> 入参类型
 */
public final class SdkTypedParameters<I> {

  private final ObjectMapper objectMapper;
  private final JavaType inputType;

  private SdkTypedParameters(ObjectMapper objectMapper, JavaType inputType) {
    this.objectMapper = objectMapper;
    this.inputType = inputType;
  }

  /** 默认 mapper —— 对齐 SDK 其它组件(JavaTimeModule 支持 LocalDate 等时间类型)。 */
  public static ObjectMapper defaultObjectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }

  /**
   * 为 {@code concreteHandler} 解析其声明在 {@code declaringBase} 上第 {@code index} 个泛型变量,构造解析器。
   *
   * @param objectMapper 用于类型解析 + 反序列化的 mapper
   * @param concreteHandler 租户的具体 handler(持有泛型实参)
   * @param declaringBase 定义泛型变量的基类(如 {@code SdkTypedTaskHandler.class})
   * @param index 入参泛型变量下标(通常为 0 → {@code I})
   */
  public static <I> SdkTypedParameters<I> forHandler(
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
  public I parse(SdkTaskContext ctx) {
    return objectMapper.convertValue(ctx.parameters(), inputType);
  }

  /** 把业务结果序列化为 output map(null → 空 map)。 */
  public Map<String, Object> toOutputMap(Object output) {
    if (output == null) {
      return Map.of();
    }
    JavaType mapType =
        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
    return objectMapper.convertValue(output, mapType);
  }
}
