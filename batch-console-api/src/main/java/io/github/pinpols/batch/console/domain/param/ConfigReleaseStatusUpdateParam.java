package io.github.pinpols.batch.console.domain.param;

import java.time.Instant;
import lombok.Builder;

/** 发布状态推进的 CAS 输入；保留期望版本、前态及可空发布时间。 */
@Builder
public record ConfigReleaseStatusUpdateParam(
    String tenantId,
    Long releaseId,
    String nextStatus,
    String expectedStatus,
    Integer expectedVersionNo,
    Instant publishedAt,
    Instant rolledBackAt,
    String updatedBy) {}
