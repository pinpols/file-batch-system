package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import java.time.Instant;

/** 数据质量规则的 HTTP 响应投影。 */
public record DataQualityRuleResponse(
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

  public static DataQualityRuleResponse from(DataQualityRuleEntity entity) {
    return new DataQualityRuleResponse(
        entity.getId(),
        entity.getTenantId(),
        entity.getRuleCode(),
        entity.getRuleName(),
        entity.getRuleType(),
        entity.getScopeBusinessKey(),
        entity.getExpression(),
        entity.getThresholdJson(),
        entity.getSeverity(),
        entity.getEnabled(),
        entity.getDescription(),
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }
}
