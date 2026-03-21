package com.example.batch.orchestrator.application.route;

import com.example.batch.common.model.WorkerRouteModel;

public interface WorkerRouter {

    WorkerRouteModel route(String tenantId, String jobCode, String stepCode);
}
