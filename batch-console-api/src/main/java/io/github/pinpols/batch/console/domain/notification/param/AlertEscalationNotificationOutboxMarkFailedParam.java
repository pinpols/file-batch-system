package io.github.pinpols.batch.console.domain.notification.param;

import java.time.Instant;
import lombok.Builder;

/** 绑定一次markFailed操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record AlertEscalationNotificationOutboxMarkFailedParam(
    Long id,
    String tenantId,
    String failedStatus,
    Instant nextPublishAt,
    String lastError,
    String publishingStatus) {}
