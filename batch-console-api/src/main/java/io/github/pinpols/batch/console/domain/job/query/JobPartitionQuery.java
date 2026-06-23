package io.github.pinpols.batch.console.domain.job.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record JobPartitionQuery(
    String tenantId,
    Long jobInstanceId,
    String partitionStatus,
    PageRequest pageRequest,
    Long cursorId) {}
