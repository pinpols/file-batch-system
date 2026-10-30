package io.github.pinpols.batch.console.domain.governance.application.contract.response;

import java.time.Instant;
import lombok.Builder;

@Builder
public record ConsoleDeadLetterTaskResponse(
    Long id,
    String tenantId,
    String sourceType,
    Long sourceId,
    String deadLetterReason,
    String errorClass,
    String payloadRef,
    String replayStatus,
    Integer replayCount,
    Instant lastReplayAt,
    String lastReplayResult,
    String traceId,
    Instant createdAt,
    Instant updatedAt) {}
