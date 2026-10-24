package io.github.pinpols.batch.console.domain.rbac.param;

import lombok.Builder;

/** 绑定一次upsert操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ConsoleResourceTagUpsertParam(
    String tenantId,
    String resourceType,
    String resourceCode,
    String tagKey,
    String tagValue,
    String operator) {}
