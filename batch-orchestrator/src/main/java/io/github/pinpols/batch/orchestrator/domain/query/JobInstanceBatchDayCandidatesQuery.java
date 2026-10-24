package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.LocalDate;
import java.util.List;
import lombok.Builder;

/** 绑定一次selectBatchDayCandidates操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record JobInstanceBatchDayCandidatesQuery(
    String tenantId,
    String calendarCode,
    LocalDate bizDate,
    List<String> statuses,
    List<String> jobCodes) {}
