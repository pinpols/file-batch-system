package io.github.pinpols.batch.ext.sample.handlers;

import io.github.pinpols.batch.sdk.handler.SdkAbstractAtomicHandler;
import io.github.pinpols.batch.sdk.task.SdkTaskContext;

/**
 * ADR-036 Atomic 模板 sample —— 单次原子调用 demo。把 parameters 原样回吐。
 *
 * <p>真业务:租户在 doInvoke 里跑自家 SQL DML / 调外部 HTTP / 纯计算,返回值进 output。
 */
public class AtomicEchoHandler extends SdkAbstractAtomicHandler<Object> {

  /** 示例仅计算、等待或记录日志；接入真实写入或推送后必须重新评估此声明。 */
  @Override
  public boolean isSafeToReplay(SdkTaskContext ctx) {
    return true;
  }

  @Override
  public String taskType() {
    return "sample_atomic_echo";
  }

  @Override
  protected Object doInvoke(SdkTaskContext ctx) {
    // 原子单调用:这里直接回吐参数;真业务替换为 DB/HTTP/compute
    return ctx.parameters().get("value");
  }
}
