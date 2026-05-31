package com.example.batch.sdk.handler;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** ADR-036 — Dispatch 模板:tenant → external push(DB → HTTP/SFTP)。 */
@Slf4j
public abstract class SdkAbstractDispatchHandler<R> extends SdkAbstractTaskHandler {
  /** 选出待推送的 payload 列表。 */
  protected abstract List<R> selectPayload(SdkTaskContext ctx) throws Exception;

  /** 单条 payload 构造外部请求对象。 */
  protected abstract Object buildRequest(SdkTaskContext ctx, R item) throws Exception;

  /** 推送单条请求,返回响应。 */
  protected abstract Object push(SdkTaskContext ctx, Object request) throws Exception;

  /** 可选:处理单条响应(记录 / 回写状态)。默认 no-op。 */
  protected void onResponse(SdkTaskContext ctx, R item, Object response) throws Exception {}

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    try {
      SdkRowResult counts = new SdkRowResult();
      List<R> items = selectPayload(ctx);
      for (R item : items) {
        try {
          Object req = buildRequest(ctx, item);
          Object resp = push(ctx, req);
          onResponse(ctx, item, resp);
          counts.incSuccess();
        } catch (Exception itemEx) {
          counts.incFailed();
          log.warn("dispatch item failed: {}", itemEx.getMessage());
        }
      }
      return SdkTaskResult.ok(
          "dispatched " + counts.success() + "/" + counts.total(), counts.toOutput());
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }
}
