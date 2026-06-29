package io.github.pinpols.batch.orchestrator.application.service.asset;

import java.time.LocalDate;

/**
 * BFS 最小 asset partition 读模型。
 *
 * <p>当前第一阶段不新增物理表，直接把 {@code result_version.business_key=job:{jobCode}:{bizDate}} 投影为 asset
 * partition。后续若引入 {@code data_asset/asset_partition} 表，本 record 的调用契约不变。
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
