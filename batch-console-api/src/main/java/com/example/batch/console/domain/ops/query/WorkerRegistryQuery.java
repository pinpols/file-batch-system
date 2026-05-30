package com.example.batch.console.domain.ops.query;

import com.example.batch.common.model.PageRequest;

public record WorkerRegistryQuery(
    String tenantId, String workerGroup, String status, PageRequest pageRequest) {}
