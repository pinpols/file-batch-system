package com.example.batch.worker.atomic.route;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.worker.atomic.domain.AtomicWorkerType;
import com.example.batch.worker.core.route.WorkerRouteAdapter;
import org.springframework.stereotype.Component;

/** 专用 SPI worker 默认路由适配器:声明 worker_type=ATOMIC。 */
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
