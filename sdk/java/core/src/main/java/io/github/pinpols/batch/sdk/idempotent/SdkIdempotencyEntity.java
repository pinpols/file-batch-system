package io.github.pinpols.batch.sdk.idempotent;

import io.github.pinpols.batch.sdk.task.SdkTaskResult;
import java.util.Map;

/**
 * A.3 — 幂等记录快照:命中时框架据此重建一个「成功」的 {@link SdkTaskResult} 返回(不重跑业务)。
 *
 * <p>只存成功结果的 message + output(去重语义针对成功执行;失败本就该重试,不记录)。output 为不可变拷贝。
 *
 * @param message 原成功 message
 * @param output 原成功 output(下游 step / job 的 runtimeAttributes)
 */
public record SdkIdempotencyEntity(String message, Map<String, Object> output) {

  public SdkIdempotencyEntity {
    output = output == null ? Map.of() : Map.copyOf(output);
  }

  /** 从一次成功结果抽快照。 */
  public static SdkIdempotencyEntity ofResult(SdkTaskResult result) {
    return new SdkIdempotencyEntity(result.message(), result.output());
  }

  /** 重建成功结果(命中跳过执行时返回)。 */
  public SdkTaskResult toResult() {
    return SdkTaskResult.ok(message, output);
  }
}
