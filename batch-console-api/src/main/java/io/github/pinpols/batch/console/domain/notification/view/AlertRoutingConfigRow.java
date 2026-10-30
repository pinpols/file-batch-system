package io.github.pinpols.batch.console.domain.notification.view;

import java.time.Instant;

/**
 * batch.alert_routing_config 行投影（AlertRoutingConfigMapper.selectByQuery / selectByUniqueKey /
 * selectById，全表 19 列）。
 *
 * <p>{@code selectByQuery} 显式 15 列并以 null 补齐 created_by / updated_by / is_deleted，保持 list 端点历史
 * wire 键集；{@code selectByUniqueKey} / {@code selectById} 覆盖全列。
 */
public record AlertRoutingConfigRow(
    Long id,
    String tenantId,
    String routeCode,
    String routeName,
    String team,
    String alertGroup,
    String severity,
    String receiver,
    String groupBy,
    Integer groupWaitSeconds,
    Integer groupIntervalSeconds,
    Integer repeatIntervalSeconds,
    Boolean enabled,
    String description,
    String createdBy,
    String updatedBy,
    Instant createdAt,
    Instant updatedAt,
    Boolean isDeleted) {}
