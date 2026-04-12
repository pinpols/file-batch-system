package com.example.batch.orchestrator.application.route;

import com.example.batch.common.model.WorkerRouteModel;
import java.util.List;

public interface WorkerRoutingPolicy {

  WorkerRouteModel select(List<WorkerRouteModel> candidates);
}
