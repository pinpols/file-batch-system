package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

/** 绑定一次failRejectedLaunches操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record BatchDayReplayEntryFailRejectedLaunchesParam(
    String tenantId,
    Long sessionId,
    Instant now,
    String failedStatus,
    List<String> rejectedStatuses) {}
