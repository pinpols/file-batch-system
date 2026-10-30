package io.github.pinpols.batch.console.support;

import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.console.domain.param.ConfigChangeLogInsertParam;

/**
 * 统一构造 {@code config_change_log} 固定插入参数的链式 Builder，集中配置写入与审批入口的默认审计字段。
 *
 * <p>用法：
 *
 * <pre>{@code
 * configChangeLogMapper.insertConfigChangeLog(
 *     ConfigChangeLogBuilder.create(tenantId, operatorId, traceId)
 *         .forType("BATCH_WINDOW")
 *         .withKey(row.windowCode())
 *         .action(action)
 *         .summary(changeSummaryJson(reason, detail))
 *         .build());
 * }</pre>
 *
 * <p>默认值：{@code versionNo=1}、{@code operatorType="USER"}、{@code changeResult="SUCCESS"}。 {@code
 * operatorId} 在 {@link #build()} 里走 {@link ConsoleTextSanitizer#safeInput(String, int)} 截断到 64
 * 字符，{@code traceId} 截断到 128 字符。
 */
public final class ConfigChangeLogBuilder {

  private static final int OPERATOR_ID_MAX_LENGTH = 64;
  private static final int TRACE_ID_MAX_LENGTH = 128;

  private final String tenantId;
  private final String operatorId;
  private final String traceId;

  private String configType;
  private String configKey;
  private String changeAction;
  private String changeSummaryJson;

  private Object versionNo = 1;
  private String operatorType = "USER";
  private String changeResult = "SUCCESS";

  private ConfigChangeLogBuilder(String tenantId, String operatorId, String traceId) {
    this.tenantId = tenantId;
    this.operatorId = operatorId;
    this.traceId = traceId;
  }

  public static ConfigChangeLogBuilder create(String tenantId, String operatorId, String traceId) {
    return new ConfigChangeLogBuilder(tenantId, operatorId, traceId);
  }

  public ConfigChangeLogBuilder forType(String configType) {
    this.configType = configType;
    return this;
  }

  public ConfigChangeLogBuilder withKey(String configKey) {
    this.configKey = configKey;
    return this;
  }

  public ConfigChangeLogBuilder action(String changeAction) {
    this.changeAction = changeAction;
    return this;
  }

  /** 已序列化的 JSON 字符串，直接入 {@code change_summary} 列。 */
  public ConfigChangeLogBuilder summary(String changeSummaryJson) {
    this.changeSummaryJson = changeSummaryJson;
    return this;
  }

  /** 默认 1；{@code DefaultConsoleConfigApplicationService} 等动态版本号场景用它覆盖。 */
  public ConfigChangeLogBuilder versionNo(Object versionNo) {
    this.versionNo = versionNo;
    return this;
  }

  /** 默认 {@code "USER"}；配置发布流 / 审批流等 API 入口改为 {@code "API"}。 */
  public ConfigChangeLogBuilder operatorType(String operatorType) {
    this.operatorType = operatorType;
    return this;
  }

  /** 默认 {@code "SUCCESS"}；失败路径可覆盖为 {@code "FAILED"} / {@code "SKIPPED"} 等。 */
  public ConfigChangeLogBuilder result(String changeResult) {
    this.changeResult = changeResult;
    return this;
  }

  public ConfigChangeLogInsertParam build() {
    String safeOperatorId = ConsoleTextSanitizer.safeInput(operatorId, OPERATOR_ID_MAX_LENGTH);
    String safeTraceId = ConsoleTextSanitizer.safeInput(traceId, TRACE_ID_MAX_LENGTH);
    ConfigChangeLogInsertParam param = ConfigChangeLogInsertParam.builder()
        .tenantId(tenantId)
        .configType(configType)
        .configKey(configKey)
        .versionNo(versionNo)
        .changeAction(changeAction)
        .changeResult(changeResult)
        .operatorType(operatorType)
        .operatorId(safeOperatorId)
        .traceId(safeTraceId)
        .changeSummaryJson(changeSummaryJson)
        .build();
    return param;
  }
}
