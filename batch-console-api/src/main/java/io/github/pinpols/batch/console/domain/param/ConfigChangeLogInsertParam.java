package io.github.pinpols.batch.console.domain.param;

import lombok.Builder;

/** 一次配置变更审计写入的固定字段；摘要正文仍在 JSON 边界序列化。 */
@Builder
public record ConfigChangeLogInsertParam(
    String tenantId,
    String configType,
    String configKey,
    Object versionNo,
    String changeAction,
    String changeResult,
    String operatorType,
    String operatorId,
    String traceId,
    String changeSummaryJson) {}
