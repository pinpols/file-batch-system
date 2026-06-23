package io.github.pinpols.batch.orchestrator.domain.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record JobPartitionQuery(
    String tenantId, Long jobInstanceId, String partitionStatus, PageRequest pageRequest) {}
