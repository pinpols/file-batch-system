package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import lombok.Builder;

/** 绑定一次markCompleted操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record ForensicExportLogMarkCompletedParam(
    String tenantId,
    String exportId,
    String storagePath,
    Long fileSizeBytes,
    String sha256,
    String rowCountsJson,
    Instant completedAt) {}
