package io.github.pinpols.batch.orchestrator.domain.query;

import io.github.pinpols.batch.common.model.PageRequest;
import lombok.Builder;

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
