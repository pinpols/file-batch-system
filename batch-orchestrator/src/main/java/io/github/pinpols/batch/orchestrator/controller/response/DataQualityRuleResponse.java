package io.github.pinpols.batch.orchestrator.controller.response;

import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
import java.time.Instant;
import lombok.Builder;

/** 数据质量规则的 HTTP 响应投影。 */
@Builder
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
    return DataQualityRuleResponse.builder()
        .id(entity.getId())
        .tenantId(entity.getTenantId())
        .ruleCode(entity.getRuleCode())
        .ruleName(entity.getRuleName())
        .ruleType(entity.getRuleType())
        .scopeBusinessKey(entity.getScopeBusinessKey())
        .expression(entity.getExpression())
        .thresholdJson(entity.getThresholdJson())
        .severity(entity.getSeverity())
        .enabled(entity.getEnabled())
        .description(entity.getDescription())
        .createdAt(entity.getCreatedAt())
        .updatedAt(entity.getUpdatedAt())
        .build();
  }
}
