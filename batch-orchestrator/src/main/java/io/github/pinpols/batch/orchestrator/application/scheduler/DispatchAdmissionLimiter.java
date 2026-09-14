package io.github.pinpols.batch.orchestrator.application.scheduler;

import io.github.pinpols.batch.orchestrator.domain.entity.ResourceQueueEntity;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;

/** 租户和资源队列维度的派发速率准入。 */
public interface DispatchAdmissionLimiter {

  ResourceCheck check(ResourceSchedulingRequest request, ResourceQueueEntity queue);
}
