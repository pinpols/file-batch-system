package io.github.pinpols.batch.worker.atomic.runtime;

import io.github.pinpols.batch.common.spi.task.TaskResult;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Atomic worker 失败路径的标准 error code 枚举。
 *
 * <p>背景:各 executor 历史失败构造散落着 magic string(如 {@code "SENSITIVE_DATA_IN_PARAMETERS: ..."} / {@code
 * "exit=1 stderr=..."} / {@code "sql failed: ..."}),下游消费者难以做稳定的归因 / 指标 / 告警分流。本枚举把 atomic worker
 * 的失败原因归并到一组稳定的字符串 code,通过 {@link TaskResult#output()} 的 {@code error_code} key 透出给
 * orchestrator(无需改 {@link TaskResult} SPI 签名 / batch-common 契约)。
 *
 * <p>下游(orchestrator / console / 告警链路)可基于 {@code output.get("error_code")} 字面值做归因,enum 名稳定即可。
 *
 * <p>仅在 atomic worker 失败路径填写;pipeline workers 不使用此枚举(它们走自有失败语义)。
 */
public enum AtomicErrorCode {

  /** 占位 — 成功路径不应使用,留作未来日志/对账对齐。 */
  SUCCESS,

  /** 任务超时(进程 wall-clock / SQL statement_timeout / HTTP read 超时等)。 */
  TIMEOUT,

  /** 任务被强制终止(SIGTERM / SIGKILL / process destroyForcibly)。 */
  KILLED,

  /** 安全闸拒绝:命令/域名/Schema 白名单越界、SSRF 私网拦截、敏感凭据字段命中等。 */
  SECURITY_REJECTED,

  /** 业务执行失败(命令非 0 退出、SQL 抛错、HTTP 非 2xx 等下游真错)。 */
  EXECUTION_FAILED,

  /** 入参或配置非法(必填缺失、类型错、超上限等校验性拒绝)。 */
  CONFIG_INVALID,

  /** 资源耗尽 / 强终(stdout 超 maxBytes、response body 超 maxResponseBytes 触发的截断后强终止)。 */
  RESOURCE_EXHAUSTED;

  /** output map key — 下游基于此 key 读 error code 字面值。 */
  public static final String OUTPUT_KEY = "error_code";

  /**
   * 构造带 error_code 的失败 TaskResult。等价于 {@code new TaskResult(false, message, Map.of("error_code",
   * code.name()), null)}, 但不动 TaskResult 类签名(它属于 batch-common SPI)。
   */
  public static TaskResult fail(AtomicErrorCode code, String message) {
    return new TaskResult(false, message, Map.of(OUTPUT_KEY, code.name()), null);
  }

  /** 同上,附带原始 throwable 透传到 {@link TaskResult#error()}。 */
  public static TaskResult fail(AtomicErrorCode code, String message, Throwable cause) {
    return new TaskResult(false, message, Map.of(OUTPUT_KEY, code.name()), cause);
  }

  /** 同上,允许携带额外 output 字段(如 exitCode / statusCode);output 已含 error_code 时以本枚举为准覆盖。 */
  public static TaskResult fail(
      AtomicErrorCode code, String message, Map<String, Object> extraOutput, Throwable cause) {
    Map<String, Object> merged = new LinkedHashMap<>();
    if (extraOutput != null) {
      merged.putAll(extraOutput);
    }
    merged.put(OUTPUT_KEY, code.name());
    return new TaskResult(false, message, merged, cause);
  }
}
