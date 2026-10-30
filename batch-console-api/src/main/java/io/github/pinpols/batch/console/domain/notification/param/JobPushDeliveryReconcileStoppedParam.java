package io.github.pinpols.batch.console.domain.notification.param;

import java.time.Instant;
import lombok.Builder;

/** 停止投递归并的原筛选条件，以及由具名入口固定的目标状态。 */
@Builder
public record JobPushDeliveryReconcileStoppedParam(
    String tenantId, Instant now, int maxAttempts, int limit, String targetStatus) {}
