package io.github.pinpols.batch.sdk.handler;

import io.github.pinpols.batch.sdk.handler.typed.SdkAbstractTypedDispatchHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;
import java.util.List;
import java.util.Map;

/**
 * ADR-036 — Dispatch 模板:tenant → external push(DB → HTTP/SFTP)。单条失败计 failed 不中断整批。
 *
 * <p>本类是 {@link SdkAbstractTypedDispatchHandler} 在「裸 Map 入参」下的特例:钩子只收 {@code ctx},模板循环复用 typed 基类。
 * 需要强类型入参时直接用 {@link SdkAbstractTypedDispatchHandler}。
 *
 * @param <R> payload 行类型
 */
public abstract class SdkAbstractDispatchHandler<R>
    extends SdkAbstractTypedDispatchHandler<Map<String, Object>, Void, R> {

  /** 选出待推送的 payload 列表。 */
  protected abstract List<R> selectPayload(SdkTaskContext ctx) throws Exception;

  /** 单条 payload 构造外部请求对象。 */
  protected abstract Object buildRequest(SdkTaskContext ctx, R item) throws Exception;

  /** 推送单条请求,返回响应。 */
  protected abstract Object push(SdkTaskContext ctx, Object request) throws Exception;

  /** 可选:处理单条响应(记录 / 回写状态)。默认 no-op。 */
  protected void onResponse(SdkTaskContext ctx, R item, Object response) throws Exception {}

  @Override
  protected final List<R> selectPayload(Map<String, Object> input, SdkTaskContext ctx)
      throws Exception {
    return selectPayload(ctx);
  }

  @Override
  protected final Object buildRequest(Map<String, Object> input, SdkTaskContext ctx, R item)
      throws Exception {
    return buildRequest(ctx, item);
  }

  @Override
  protected final Object push(Map<String, Object> input, SdkTaskContext ctx, Object request)
      throws Exception {
    return push(ctx, request);
  }

  @Override
  protected final void onResponse(
      Map<String, Object> input, SdkTaskContext ctx, R item, Object response) throws Exception {
    onResponse(ctx, item, response);
  }
}
