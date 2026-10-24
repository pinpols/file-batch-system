package io.github.pinpols.batch.trigger.domain.param;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Builder;

/** 绑定一次transitionToCutoff操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record BatchDayInstanceTransitionToCutoffParam(
    Long id,
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    Instant cutoffAt,
    String expectedStatus,
    String targetStatus) {}
