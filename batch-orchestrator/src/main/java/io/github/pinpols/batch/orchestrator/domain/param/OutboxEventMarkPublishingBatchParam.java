package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.List;
import lombok.Builder;

/** 绑定一次markPublishingBatch操作；字段可空性、筛选与状态条件沿用原映射语句和上游校验。 */
@Builder
public record OutboxEventMarkPublishingBatchParam(
    String tenantId,
    List<Long> ids,
    String publishingStatus,
    String pendingStatus1,
    String pendingStatus2) {}
