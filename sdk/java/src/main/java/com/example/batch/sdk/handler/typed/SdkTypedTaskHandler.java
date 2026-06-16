package com.example.batch.sdk.handler.typed;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskHandler;
import com.example.batch.sdk.task.SdkTaskResult;
import com.fasterxml.jackson.databind.ObjectMapper;

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
 * <p>入参反序列化逻辑复用 {@link SdkTypedParameters}(与 handler 包的 typed 行流模板共享同一实现)。
 *
 * @param <I> 入参类型(从 parameters 反序列化)
 * @param <O> 业务结果类型(序列化进 output)
 */
public abstract class SdkTypedTaskHandler<I, O> implements SdkTaskHandler {

  private final SdkTypedParameters<I> params;

  protected SdkTypedTaskHandler() {
    this(SdkTypedParameters.defaultObjectMapper());
  }

  protected SdkTypedTaskHandler(ObjectMapper objectMapper) {
    this.params = SdkTypedParameters.forHandler(objectMapper, this, SdkTypedTaskHandler.class, 0);
  }

  @Override
  public final SdkTaskResult execute(SdkTaskContext ctx) {
    I input;
    try {
      input = params.parse(ctx);
    } catch (IllegalArgumentException ex) {
      return SdkTaskResult.fail(
          "invalid parameters for taskType=" + taskType() + ": " + ex.getMessage(), ex);
    }
    O output = handle(input, ctx);
    return SdkTaskResult.ok(successMessage(output), params.toOutputMap(output));
  }

  /** 业务实现 —— 拿强类型入参,返业务结果。抛异常即失败(框架兜底)。 */
  protected abstract O handle(I input, SdkTaskContext ctx);

  /** 成功时的 message,默认 {@code "ok"};按需重写返摘要。 */
  protected String successMessage(O output) {
    return "ok";
  }
}
