package io.github.pinpols.batch.orchestrator.domain.param;

import java.util.List;
import lombok.Builder;

/** 绑定一次 markGiveUpBatch 操作；publishAttempt 为本轮领取凭据。 */
@Builder
public record OutboxEventMarkGiveUpBatchParam(
        String tenantId, List<Long> ids, String status, String publishingStatus, int publishAttempt) {}
