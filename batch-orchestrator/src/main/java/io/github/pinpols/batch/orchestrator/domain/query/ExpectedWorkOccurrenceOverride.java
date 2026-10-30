package io.github.pinpols.batch.orchestrator.domain.query;

import java.time.Instant;
import java.time.LocalDate;

/** 批次日运维人员明确豁免、尚未解决的输入义务快照。 */
public record ExpectedWorkOccurrenceOverride(
    Long id,
    Integer generation,
    Long workflowDefinitionId,
    String sourceType,
    String sourceKey,
    LocalDate bizDate,
    String occurrenceStatus,
    Instant expectedAt,
    Instant dueAt,
    String triggerRequestId) {}
