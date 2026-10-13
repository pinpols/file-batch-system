package io.github.pinpols.batch.orchestrator.controller.request;

import io.github.pinpols.batch.orchestrator.domain.entity.DataQualityRuleEntity;
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

  public DataQualityRuleEntity toEntity() {
    DataQualityRuleEntity entity = new DataQualityRuleEntity();
    entity.setId(id);
    entity.setTenantId(tenantId);
    entity.setRuleCode(ruleCode);
    entity.setRuleName(ruleName);
    entity.setRuleType(ruleType);
    entity.setScopeBusinessKey(scopeBusinessKey);
    entity.setExpression(expression);
    entity.setThresholdJson(thresholdJson);
    entity.setSeverity(severity);
    entity.setEnabled(enabled);
    entity.setDescription(description);
    entity.setCreatedAt(createdAt);
    entity.setUpdatedAt(updatedAt);
    return entity;
  }
}
