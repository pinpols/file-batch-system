package io.github.pinpols.batch.worker.processes.param;

import lombok.Builder;

/** 绑定一次selectCurrentStagingGeneration操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ProcessInvocationFenceSelectCurrentStagingGenerationQuery(
    String tenantId,
    Long taskId,
    Long jobInstanceId,
    Long jobPartitionId,
    String workerId,
    String invocationId) {}
