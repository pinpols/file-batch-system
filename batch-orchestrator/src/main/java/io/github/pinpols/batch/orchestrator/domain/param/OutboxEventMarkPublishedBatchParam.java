package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.List;
import lombok.Builder;

/** 绑定一次 markPublishedBatch 操作；publishAttempt 为本轮领取凭据。 */
@Builder
public record OutboxEventMarkPublishedBatchParam(
    String tenantId, List<Long> ids, String status, String publishingStatus, int publishAttempt) {}
