package io.github.pinpols.batch.console.domain.ops.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record WorkerRegistryQuery(
    String tenantId, String workerGroup, String status, PageRequest pageRequest) {}
