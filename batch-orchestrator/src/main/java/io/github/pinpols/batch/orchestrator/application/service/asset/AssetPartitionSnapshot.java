package io.github.pinpols.batch.orchestrator.application.service.asset;

import java.time.LocalDate;

/**
 * BFS 最小 asset partition 读模型。
 *
 * <p>物化表 {@code data_asset/asset_partition} 是查询优化和 readiness 统一口径；权威版本链仍是 {@code
 * result_version.business_key=job:{jobCode}:{bizDate}} 的当前 EFFECTIVE 行。
 */
public record AssetPartitionSnapshot(
    String tenantId,
    String assetCode,
    LocalDate bizDate,
    String partitionKey,
    String businessKey,
    String freshnessStatus,
    Integer versionNo,
    Long jobInstanceId,
    String payloadStorage,
    String payloadJson,
    String payloadRef) {}
