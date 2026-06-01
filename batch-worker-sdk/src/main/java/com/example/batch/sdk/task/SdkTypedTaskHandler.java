package com.example.batch.sdk.task;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.Map;

/**
 * 类型安全的任务执行基类 — SDK Phase 5 / SDK-P5-1。
 *
 * <p>租户写 handler 不再用 {@code Map<String, Object>} 瞎转型:框架在调用前把 {@link SdkTaskContext#parameters()} 经
 * Jackson 反序列化成强类型入参 {@code I},handler 返回业务结果 {@code O},框架再把它序列化进 {@link
 * SdkTaskResult#output()}。编译期即校验入参 / 出参类型。
 *
 * <p>典型实现:
 *
 * <pre>{@code
 * public class MyImportHandler extends SdkTypedTaskHandler<ImportRequest, ImportResult> {
 *   @Override public String taskType() { return "tenant_xyz_import"; }
 *   @Override protected ImportResult handle(ImportRequest req, SdkTaskContext ctx) {
 *     int n = importRows(req.sourcePath());     // 直接拿强类型字段,无需 cast
 *     return new ImportResult(n);               // 框架转 output map
 *   }
 * }
 * }</pre>
 *
 * <p>语义约定:
 *
 * <ul>
 *   <li>入参反序列化失败(参数结构不匹配)→ 框架直接 {@link SdkTaskResult#fail} 报错,不进业务。
 *   <li>{@link #handle} 抛任何异常 → 透传给 {@code TaskDispatcher} 统一兜底转 fail + REPORT failure。
 *   <li>{@link #handle} 正常返回 {@code O} → 成功;{@code O} 序列化为 output map(null → 空 map),message 走
 *       {@link #successMessage}(默认 {@code "ok"})。
 * </ul>
 *
 * <p>需要显式失败 / 自定义 message-without-exception 的场景,继续用原始 {@link SdkTaskHandler} 接口。
 *
 * @param <I> 入参类型(从 parameters 反序列化)
 * @param <O> 业务结果类型(序列化进 output)
 */
public abstract class SdkTypedTaskHandler<I, O> implements SdkTaskHandler {

  private final ObjectMapper objectMapper;
  private final JavaType inputType;

  protected SdkTypedTaskHandler() {
    this(defaultObjectMapper());
  }

  protected SdkTypedTaskHandler(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.inputType = resolveInputType(objectMapper);
  }

  /** 默认 mapper —— 对齐 SDK 其它组件(JavaTimeModule 支持 LocalDate 等时间类型)。 */
  private static ObjectMapper defaultObjectMapper() {
    return new ObjectMapper().registerModule(new JavaTimeModule());
  }

  /** 从泛型超类解析 {@code I} 的 Jackson 类型;无法解析(裸用泛型)→ 退化为 Object。 */
  private JavaType resolveInputType(ObjectMapper mapper) {
    JavaType self = mapper.getTypeFactory().constructType(getClass());
    JavaType[] params = mapper.getTypeFactory().findTypeParameters(self, SdkTypedTaskHandler.class);
    if (params == null || params.length == 0) {
      return mapper.getTypeFactory().constructType(Object.class);
    }
    return params[0];
  }

  @Override
  public final SdkTaskResult execute(SdkTaskContext ctx) {
    I input;
    try {
      input = objectMapper.convertValue(ctx.parameters(), inputType);
    } catch (IllegalArgumentException ex) {
      return SdkTaskResult.fail(
          "invalid parameters for taskType=" + taskType() + ": " + ex.getMessage(), ex);
    }
    O output = handle(input, ctx);
    return SdkTaskResult.ok(successMessage(output), toOutputMap(output));
  }

  /** 业务实现 —— 拿强类型入参,返业务结果。抛异常即失败(框架兜底)。 */
  protected abstract O handle(I input, SdkTaskContext ctx);

  /** 成功时的 message,默认 {@code "ok"};按需重写返摘要。 */
  protected String successMessage(O output) {
    return "ok";
  }

  private Map<String, Object> toOutputMap(O output) {
    if (output == null) {
      return Map.of();
    }
    JavaType mapType =
        objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
    return objectMapper.convertValue(output, mapType);
  }
}
