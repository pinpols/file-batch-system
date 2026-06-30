package io.github.pinpols.batch.orchestrator.application.service.asset;

import java.time.Instant;
import java.time.LocalDate;

/** 写入 JOB asset partition 当前 EFFECTIVE 状态的参数对象。 */
public record AssetPartitionMaterializationCommand(
    String tenantId,
    Long assetId,
    String assetCode,
    String partitionKey,
    LocalDate bizDate,
    Long resultVersionId,
    String businessKey,
    Long jobInstanceId,
    Instant effectiveAt,
    String payloadStorage,
    String payloadRef) {}
