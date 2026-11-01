package io.github.pinpols.batch.orchestrator.controller.request;

import io.github.pinpols.batch.orchestrator.domain.command.DataQualityRuleWriteCommand;
import java.time.Instant;

/** 创建或替换数据质量规则的 HTTP 写入契约。 */
public record DataQualityRuleRequest(
    Long id,
    String tenantId,
    String ruleCode,
    String ruleName,
    String ruleType,
    String scopeBusinessKey,
    String expression,
    String thresholdJson,
    String severity,
    Boolean enabled,
    String description,
    Instant createdAt,
    Instant updatedAt) {

  public DataQualityRuleWriteCommand toCommand() {
    return DataQualityRuleWriteCommand.builder()
        .id(id)
        .tenantId(tenantId)
        .ruleCode(ruleCode)
        .ruleName(ruleName)
        .ruleType(ruleType)
        .scopeBusinessKey(scopeBusinessKey)
        .expression(expression)
        .thresholdJson(thresholdJson)
        .severity(severity)
        .enabled(enabled)
        .description(description)
        .createdAt(createdAt)
        .updatedAt(updatedAt)
        .build();
  }
}
