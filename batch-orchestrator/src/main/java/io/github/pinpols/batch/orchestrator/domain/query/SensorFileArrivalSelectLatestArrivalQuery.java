package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Builder;

/** 绑定一次selectLatestArrival操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record SensorFileArrivalSelectLatestArrivalQuery(
    String tenantId,
    String namePattern,
    String sourceType,
    OffsetDateTime since,
    LocalDate bizDate,
    String fileGroupCode,
    Long workflowDefinitionId,
    boolean requireCompleteGroup) {}
