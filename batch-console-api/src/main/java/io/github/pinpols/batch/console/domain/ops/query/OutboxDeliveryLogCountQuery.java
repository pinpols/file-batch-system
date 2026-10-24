package io.github.pinpols.batch.console.domain.ops.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;
/** 绑定一次countByQuery操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record OutboxDeliveryLogCountQuery(
    String tenantId,
    String deliveryStatus,
    String eventType,
    String eventKey,
    String traceId,
    PageRequest pageRequest) {}
