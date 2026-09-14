package io.github.pinpols.batch.orchestrator.application.scheduler;

import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceCheck;
import io.github.pinpols.batch.orchestrator.domain.scheduling.ResourceSchedulingRequest;

/** 外部交付依赖健康状态的统一调度准入。 */
public interface DownstreamAdmissionGuard {

  ResourceCheck check(ResourceSchedulingRequest request);
}
