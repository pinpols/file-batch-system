package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.List;
import lombok.Builder;

/** 绑定一次updateAcceptanceGuarded操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record TriggerRequestUpdateAcceptanceGuardedParam(
    String tenantId,
    String requestId,
    String requestStatus,
    Long relatedJobInstanceId,
    List<String> protectedStatuses,
    String launchedStatus) {}
