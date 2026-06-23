package io.github.pinpols.batch.console.domain.job.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record JobStepInstanceQuery(
    String tenantId,
    Long jobInstanceId,
    Long jobPartitionId,
    String stepCode,
    String stepStatus,
    PageRequest pageRequest,
    Long cursorId) {}
