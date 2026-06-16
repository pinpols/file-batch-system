package com.example.batch.sdk.handler;

import com.example.batch.sdk.task.SdkTaskContext;
import com.example.batch.sdk.task.SdkTaskResult;
import java.util.Map;

/**
 * ADR-036 — Atomic 业务模板:单次原子调用(shell / sql 单语句 / HTTP / pure compute)。 子类只实现 doInvoke,异常自动转
 * fail,不用拼 SdkTaskResult。 对应平台 batch-worker-atomic 的 shell/sql/stored-proc/http,SDK 侧由租户自实现。
 */
public abstract class SdkAbstractAtomicHandler<R> extends SdkAbstractTaskHandler {

  /** 子类实现单次原子调用。 */
  protected abstract R doInvoke(SdkTaskContext ctx) throws Exception;

  /** 可选:把返回值映射成 output(默认 {"result": r},r 为 null 则空 Map)。 */
  protected Map<String, Object> asOutput(R result) {
    return result == null ? Map.of() : Map.of("result", result);
  }

  @Override
  protected final SdkTaskResult doExecute(SdkTaskContext ctx) {
    try {
      return SdkTaskResult.ok("invoked", asOutput(doInvoke(ctx)));
    } catch (Exception e) {
      return SdkTaskResult.fail(e);
    }
  }
}
