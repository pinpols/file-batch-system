package io.github.pinpols.batch.worker.processes.route;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.worker.core.route.WorkerRouteAdapter;
import io.github.pinpols.batch.worker.processes.domain.ProcessWorkerType;
import org.springframework.stereotype.Component;

/** 处理 Worker 默认路由适配器实现。 */
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
