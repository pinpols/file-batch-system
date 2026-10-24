package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.LocalTime;
import lombok.Builder;

/** 绑定一次upsert操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record FileGroupExpectationDefinitionUpsertParam(
    String tenantId,
    String calendarCode,
    Long workflowDefinitionId,
    String fileGroupCode,
    LocalTime expectedLocalTime,
    LocalTime dueLocalTime,
    boolean enabled) {}
