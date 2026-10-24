package io.github.pinpols.batch.console.domain.audit.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;
import java.time.Instant;
/** 绑定一次query操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record OperationAuditQuery(
    String tenantId,
    String aggregateType,
    String aggregateId,
    String action,
    String operatorId,
    String result,
    String traceId,
    Instant startTime,
    Instant endTime,
    Long cursorId,
    PageRequest pageRequest) {}
