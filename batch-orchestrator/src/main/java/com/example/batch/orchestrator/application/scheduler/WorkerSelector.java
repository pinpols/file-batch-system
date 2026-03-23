package com.example.batch.orchestrator.application.scheduler;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;
import com.example.batch.orchestrator.domain.scheduler.ResourceSchedulingRequest;

public interface WorkerSelector {

    WorkerRouteModel select(ResourceSchedulingRequest request, ResourceQueueRecord queue, Integer priority);
}
