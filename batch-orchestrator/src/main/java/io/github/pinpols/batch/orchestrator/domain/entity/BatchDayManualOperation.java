package io.github.pinpols.batch.orchestrator.domain.entity;

import java.time.Instant;
import lombok.Builder;

@Builder
public record BatchDayManualOperation(
    String dayStatus,
    Boolean frozen,
    String operationReason,
    String operatedBy,
    Instant operatedAt,
    Instant settledAt,
    Instant updatedAt) {}
