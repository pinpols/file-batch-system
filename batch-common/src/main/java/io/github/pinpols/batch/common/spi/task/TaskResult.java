package io.github.pinpols.batch.common.spi.task;

import java.util.Map;
import java.util.Objects;

/**
 * {@link BatchTaskExecutor#execute(TaskContext)} 的返回 — 成功 / 失败 + 业务输出 + 错误透传。
 *
 * <p>{@code output} 用于把执行结果传给下游(report → outbox → 后续 step / job)。约定:
 *
 * <ul>
 *   <li>Shell 任务:{@code exitCode} / {@code stdout} / {@code stderr}
 *   <li>SQL 任务:{@code affectedRows} / {@code resultSetUri}
 *   <li>HTTP 任务:{@code statusCode} / {@code responseBody}
 *   <li>业务自定义:按需要约定 key
 * </ul>
 *
 * <p>{@code error} 仅在失败时非空,框架会把它转成 i18n LocalizedError 透传到 orchestrator 持久化。
 *
 * @param success 成功 / 失败
 * @param message 描述信息(成功 = 摘要;失败 = 错误文本)
 * @param output 业务输出,不可为 null(空 → {@code Map.of()})
 * @param error 失败时的根因异常,可空
 */
public record TaskResult(
    boolean success, String message, Map<String, Object> output, Throwable error) {

  public TaskResult {
    output = output == null ? Map.of() : Map.copyOf(output);
  }

  public static TaskResult ok() {
    return new TaskResult(true, "ok", Map.of(), null);
  }

  public static TaskResult ok(String message) {
    return new TaskResult(true, message, Map.of(), null);
  }

  public static TaskResult ok(Map<String, Object> output) {
    return new TaskResult(true, "ok", output, null);
  }

  public static TaskResult ok(String message, Map<String, Object> output) {
    return new TaskResult(true, message, output, null);
  }

  public static TaskResult fail(String message) {
    Objects.requireNonNull(message, "message");
    return new TaskResult(false, message, Map.of(), null);
  }

  public static TaskResult fail(Throwable error) {
    Objects.requireNonNull(error, "error");
    String msg = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    return new TaskResult(false, msg, Map.of(), error);
  }

  public static TaskResult fail(String message, Throwable error) {
    Objects.requireNonNull(message, "message");
    return new TaskResult(false, message, Map.of(), error);
  }
}
