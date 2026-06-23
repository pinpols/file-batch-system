package io.github.pinpols.batch.sdk.handler.typed;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.sdk.handler.SdkAbstractTaskHandler;
import io.github.pinpols.batch.sdk.handler.SdkRowResult;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * A.2 — typed Dispatch 模板:tenant → external push(DB → HTTP/SFTP),把强类型入参与 ADR-036 Dispatch 模板合流。
 *
 * <p>模板序:{@code selectPayload → buildRequest(逐条) → push → onResponse}。单条失败计 failed 不中断整批 (与非 typed
 * 基类一致)。租户拿强类型入参 {@code I},复用 {@link SdkTypedParameters} 解析(组合)。
 *
 * @param <I> 强类型入参(从 parameters 反序列化)
 * @param <O> 业务结果(序列化进 output;返 null 则走计数器 output)
 * @param <R> payload 行类型
 */
@Slf4j
public abstract class SdkAbstractTypedDispatchHandler<I, O, R> extends SdkAbstractTaskHandler {

  private final SdkTypedParameters<I> params;

  protected SdkAbstractTypedDispatchHandler() {
    this(SdkTypedParameters.defaultObjectMapper());
  }

  protected SdkAbstractTypedDispatchHandler(ObjectMapper objectMapper) {
    this.params =
        SdkTypedParameters.forHandler(objectMapper, this, SdkAbstractTypedDispatchHandler.class, 0);
  }

  /** 选出待推送的 payload 列表。 */
  protected abstract List<R> selectPayload(I input, SdkTaskContext ctx) throws Exception;

  /** 单条 payload 构造外部请求对象。 */
  protected abstract Object buildRequest(I input, SdkTaskContext ctx, R item) throws Exception;

  /** 推送单条请求,返回响应。 */
  protected abstract Object push(I input, SdkTaskContext ctx, Object request) throws Exception;

  /** 可选:处理单条响应(记录 / 回写状态)。默认 no-op。 */
  protected void onResponse(I input, SdkTaskContext ctx, R item, Object response)
      throws Exception {}

  /** 汇总成业务结果 {@code O};默认返 null。 */
  protected O summarize(I input, SdkRowResult counts) {
    return null;
  }

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    I input;
    try {
      input = params.parse(ctx);
    } catch (IllegalArgumentException ex) {
      return SdkTaskResult.fail(
          "invalid parameters for taskType=" + taskType() + ": " + ex.getMessage(), ex);
    }
    try {
      SdkRowResult counts = new SdkRowResult();
      List<R> items = selectPayload(input, ctx);
      for (R item : items) {
        try {
          Object req = buildRequest(input, ctx, item);
          Object resp = push(input, ctx, req);
          onResponse(input, ctx, item, resp);
          counts.incSuccess();
        } catch (Exception itemEx) {
          counts.incFailed();
          log.warn("typed dispatch item failed: {}", itemEx.getMessage());
        }
      }
      return result(input, counts, "dispatched " + counts.success() + "/" + counts.total());
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }

  private SdkTaskResult result(I input, SdkRowResult counts, String defaultMessage) {
    O output = summarize(input, counts);
    if (output == null) {
      return SdkTaskResult.ok(defaultMessage, counts.toOutput());
    }
    return SdkTaskResult.ok(defaultMessage, params.toOutputMap(output));
  }
}
