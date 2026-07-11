package io.github.pinpols.batch.sdk.task;

/**
 * 平台 report body {@code errorCode} 字段的规范协议常量 —— 与 {@code docs/sdk/wire-protocol.md} §B「task
 * 执行结果错误码」 表、Go {@code protocol.ErrorCode*}、TS {@code ErrorCode}、Rust {@code protocol::error_code}
 * 逐项对齐。
 *
 * <p><b>为什么要有这个类</b>:早先 Java SDK 未捕获 handler 异常时,{@code errorCode} 填的是异常类 {@code getSimpleName()}(如
 * {@code IllegalStateException} / {@code RuntimeException}),而 Go/TS/Rust 填规范常量 {@code
 * EXECUTION_FAILED}。平台按 {@code errorCode} 聚合告警时,同一类失败在不同语言 SDK 下碎片化成一堆五花八门的 异常类名,无法归并。统一到本类常量后,跨语言
 * SDK 的失败分类可机器聚合;原异常类名 / message 仍保留在 {@code resultSummary} 里维持可诊断性。
 *
 * <p><b>不是</b>穷举:handler / atomic executor 可通过 {@link SdkTaskResult} 的 {@code output['errorCode']}
 * 直接给出 业务码(如 {@link SdkTaskResult#CANCELLED_CODE}),此时以业务码为准,本类只提供「无显式码时的规范回退」。
 */
public final class SdkErrorCode {

  private SdkErrorCode() {}

  /** 成功(对应 {@code success=true};errorCode 通常不下发)。 */
  public static final String SUCCESS = "SUCCESS";

  /** 执行超 task 配置 timeout(failureClass TRANSIENT)。 */
  public static final String TIMEOUT = "TIMEOUT";

  /**
   * cancelRequested 触发的协作式主动停(failureClass TERMINAL_USER);与 {@link SdkTaskResult#CANCELLED_CODE}
   * 同值。
   */
  public static final String CANCELLED = "CANCELLED";

  /** 被强制杀停(failureClass TERMINAL_USER)。 */
  public static final String KILLED = "KILLED";

  /** SensitiveDataValidator 拦截 / 凭据放进 parameters(failureClass TERMINAL_CONFIG)。 */
  public static final String SECURITY_REJECTED = "SECURITY_REJECTED";

  /** 业务逻辑抛异常的默认回退分类(failureClass BUSINESS)—— 未捕获 handler 异常统一映射到此。 */
  public static final String EXECUTION_FAILED = "EXECUTION_FAILED";

  /** EffectiveTaskConfig 校验失败 / 缺必填(failureClass TERMINAL_CONFIG)。 */
  public static final String CONFIG_INVALID = "CONFIG_INVALID";

  /** 内存 / 磁盘 / 连接池耗尽(failureClass TRANSIENT)。 */
  public static final String RESOURCE_EXHAUSTED = "RESOURCE_EXHAUSTED";
}
