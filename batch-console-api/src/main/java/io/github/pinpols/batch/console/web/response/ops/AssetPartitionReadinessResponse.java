package io.github.pinpols.batch.console.web.response.ops;

import java.time.LocalDate;

/** 作业账期对应资产分区的就绪裁决。 */
public record AssetPartitionReadinessResponse(
    boolean ready,
    String reason,
    String assetCode,
    LocalDate bizDate,
    String partitionKey,
    String businessKey,
    String freshnessStatus,
    Integer versionNo,
    Long jobInstanceId,
    String payloadStorage,
    String payloadRef) {}
