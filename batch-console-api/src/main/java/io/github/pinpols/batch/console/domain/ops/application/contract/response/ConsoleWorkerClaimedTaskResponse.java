package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsoleWorkerClaimedTaskResponse(
    Long id,
    Long jobInstanceId,
    Long jobPartitionId,
    String taskType,
    Integer taskSeq,
    String taskStatus,
    String assignedWorkerCode,
    String errorCode,
    Instant startedAt,
    Instant finishedAt,
    Instant createdAt,
    Instant updatedAt) {}
