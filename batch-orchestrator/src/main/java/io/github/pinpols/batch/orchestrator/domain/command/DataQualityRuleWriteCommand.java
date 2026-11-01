package io.github.pinpols.batch.orchestrator.domain.command;

import java.time.Instant;
import lombok.Builder;

/** 数据质量规则写入输入。 */
@Builder
public record DataQualityRuleWriteCommand(
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
    Instant updatedAt) {}
