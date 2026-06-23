package io.github.pinpols.batch.orchestrator.domain.query;

import io.github.pinpols.batch.common.model.PageRequest;

public record JobInstanceQuery(
    String tenantId, String jobCode, String instanceStatus, PageRequest pageRequest) {}
