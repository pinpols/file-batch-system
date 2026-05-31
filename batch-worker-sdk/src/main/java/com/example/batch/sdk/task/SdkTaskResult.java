package com.example.batch.sdk.task;

import java.util.Map;
import java.util.Objects;

/**
 * 任务执行结果 — {@link SdkTaskHandler#execute(SdkTaskContext)} 返回。SDK 会自动转成平台 REPORT 协议。
 *
 * @param success true=成功(orchestrator 推进 SUCCESS),false=失败(推进 FAILED + 触发重试 / 补偿)
 * @param message 描述信息(成功 = 摘要,失败 = 错误文本;orchestrator 会落 audit_log)
 * @param output 业务输出 Map,会作为下游 step / job 的 runtimeAttributes 传递
 * @param error 失败时的根因异常(可空),SDK 会序列化 stacktrace 透传给 orchestrator
 */
public record SdkTaskResult(
    boolean success, String message, Map<String, Object> output, Throwable error) {

  public SdkTaskResult {
    output = output == null ? Map.of() : Map.copyOf(output);
  }

  public static SdkTaskResult ok() {
    return new SdkTaskResult(true, "ok", Map.of(), null);
  }

  public static SdkTaskResult ok(String message) {
    return new SdkTaskResult(true, message, Map.of(), null);
  }

  public static SdkTaskResult ok(String message, Map<String, Object> output) {
    return new SdkTaskResult(true, message, output, null);
  }

  public static SdkTaskResult fail(String message) {
    Objects.requireNonNull(message, "message");
    return new SdkTaskResult(false, message, Map.of(), null);
  }

  public static SdkTaskResult fail(Throwable error) {
    Objects.requireNonNull(error, "error");
    return new SdkTaskResult(
        false,
        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
        Map.of(),
        error);
  }

  public static SdkTaskResult fail(String message, Throwable error) {
    Objects.requireNonNull(message, "message");
    return new SdkTaskResult(false, message, Map.of(), error);
  }
}
