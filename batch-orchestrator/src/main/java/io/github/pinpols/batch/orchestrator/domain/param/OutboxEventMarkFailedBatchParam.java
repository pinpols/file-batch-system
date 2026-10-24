package io.github.pinpols.batch.orchestrator.domain.param;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

/** 绑定一次markFailedBatch操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record OutboxEventMarkFailedBatchParam(
    String tenantId,
    List<Long> ids,
    String status,
    Instant nextPublishAt,
    String publishingStatus) {}
