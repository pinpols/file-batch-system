package com.example.batch.console.support;

import com.example.batch.common.utils.ConsoleTextSanitizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 统一构造 {@code config_change_log} 插入参数 Map 的链式 Builder，消除 12 个 Excel Service 里重复的
 * {@code configChangeLogMapper.insertConfigChangeLog(mapOf(...))} 样板。
 *
 * <p>用法：
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
 * <p>默认值：{@code versionNo=1}、{@code operatorType="USER"}、{@code changeResult="SUCCESS"}。
 * {@code operatorId} 在 {@link #build()} 里走 {@link ConsoleTextSanitizer#safeInput(String, int)}
 * 截断到 64 字符，{@code traceId} 截断到 128 字符。
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

  public Map<String, Object> build() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("tenantId", tenantId);
    values.put("configType", configType);
    values.put("configKey", configKey);
    values.put("versionNo", versionNo);
    values.put("changeAction", changeAction);
    values.put("changeResult", changeResult);
    values.put("operatorType", operatorType);
    values.put("operatorId", ConsoleTextSanitizer.safeInput(operatorId, OPERATOR_ID_MAX_LENGTH));
    values.put("traceId", ConsoleTextSanitizer.safeInput(traceId, TRACE_ID_MAX_LENGTH));
    values.put("changeSummaryJson", changeSummaryJson);
    return values;
  }
}
