package io.github.pinpols.batch.orchestrator.domain.query;

import lombok.Builder;

import io.github.pinpols.batch.common.model.PageRequest;

@Builder
public record OutboxEventQuery(
        String tenantId,
        String publishStatus,
        String aggregateType,
        PageRequest pageRequest,
        String pendingStatus1,
        String pendingStatus2,
        Integer batchSize,
        Integer shardTotal,
        Integer shardIndex) {}
