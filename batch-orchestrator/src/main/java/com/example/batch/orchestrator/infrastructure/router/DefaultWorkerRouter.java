package com.example.batch.orchestrator.infrastructure.router;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.orchestrator.application.route.WorkerRouter;
import com.example.batch.orchestrator.application.route.WorkerRoutingPolicy;
import org.springframework.stereotype.Component;

@Component
public class DefaultWorkerRouter implements WorkerRouter {

    private final WorkerRoutingPolicy workerRoutingPolicy;

    public DefaultWorkerRouter(WorkerRoutingPolicy workerRoutingPolicy) {
        this.workerRoutingPolicy = workerRoutingPolicy;
    }

    @Override
    public WorkerRouteModel route(String tenantId, String jobCode, String stepCode) {
        WorkerRouteModel route = new WorkerRouteModel();
        route.setWorkerId(tenantId + ":" + jobCode + ":" + stepCode);
        route.setWorkerType(stepCode == null || stepCode.isBlank() ? "DEFAULT" : stepCode.toUpperCase());
        route.setPriority(5);
        route.setAvailable(true);
        WorkerRouteModel selected = workerRoutingPolicy.select(java.util.List.of(route));
        return selected == null ? route : selected;
    }
}
