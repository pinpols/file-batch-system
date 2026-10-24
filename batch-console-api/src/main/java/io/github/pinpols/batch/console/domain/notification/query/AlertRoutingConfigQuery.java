package io.github.pinpols.batch.console.domain.notification.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;
/** 绑定一次selectByQuery操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record AlertRoutingConfigQuery(
    String tenantId,
    String keyword,
    String routeCode,
    String team,
    String severity,
    Boolean enabled,
    PageRequest pageRequest) {}
