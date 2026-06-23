package io.github.pinpols.batch.console.domain.job.web.response;

import java.time.Instant;

public record ConsoleJobPartitionResponse(
    Long id,
    String tenantId,
    Long jobInstanceId,
    Integer partitionNo,
    String partitionKey,
    String partitionStatus,
    String workerGroup,
    String workerCode,
    Integer retryCount,
    String businessKey,
    Instant leaseExpireAt,
    Instant startedAt,
    Instant finishedAt) {}
