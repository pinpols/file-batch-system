package io.github.pinpols.batch.console.domain.notification.query;

import lombok.Builder;

/** 绑定一次countByQuery操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record AlertRoutingConfigCountByQueryQuery(
    String tenantId,
    String keyword,
    String routeCode,
    String team,
    String severity,
    Boolean enabled) {}
