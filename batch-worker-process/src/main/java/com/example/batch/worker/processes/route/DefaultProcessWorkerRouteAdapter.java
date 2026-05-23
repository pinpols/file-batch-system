package com.example.batch.worker.processes.route;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.worker.core.route.WorkerRouteAdapter;
import com.example.batch.worker.processes.domain.ProcessWorkerType;
import org.springframework.stereotype.Component;

@Component
public class DefaultProcessWorkerRouteAdapter implements WorkerRouteAdapter {

  @Override
  public WorkerRouteModel buildDefaultRoute() {
    WorkerRouteModel model = new WorkerRouteModel();
    model.setWorkerType(ProcessWorkerType.PROCESS);
    model.setAvailable(Boolean.TRUE);
    return model;
  }
}
