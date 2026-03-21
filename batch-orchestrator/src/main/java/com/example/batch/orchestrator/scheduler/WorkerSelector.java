package com.example.batch.orchestrator.scheduler;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.domain.entity.ResourceQueueRecord;

public interface WorkerSelector {

    WorkerRouteModel select(ResourceSchedulingRequest request, ResourceQueueRecord queue, Integer priority);
}
