package io.github.pinpols.batch.worker.atomic.route;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.worker.atomic.domain.AtomicWorkerType;
import io.github.pinpols.batch.worker.core.route.WorkerRouteAdapter;
import org.springframework.stereotype.Component;

/** 专用原子任务 worker 默认路由适配器:声明 worker_type=ATOMIC。 */
@Component
public class AtomicWorkerRouteAdapter implements WorkerRouteAdapter {

  @Override
  public WorkerRouteModel buildDefaultRoute() {
    WorkerRouteModel model = new WorkerRouteModel();
    model.setWorkerType(AtomicWorkerType.ATOMIC);
    model.setAvailable(Boolean.TRUE);
    return model;
  }
}
