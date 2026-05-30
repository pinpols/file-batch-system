package com.example.batch.worker.spi.route;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.worker.core.route.WorkerRouteAdapter;
import com.example.batch.worker.spi.domain.SpiWorkerType;
import org.springframework.stereotype.Component;

/** 专用 Task SPI worker 默认路由适配器:声明 worker_type=TASK。 */
@Component
public class SpiWorkerRouteAdapter implements WorkerRouteAdapter {

  @Override
  public WorkerRouteModel buildDefaultRoute() {
    WorkerRouteModel model = new WorkerRouteModel();
    model.setWorkerType(SpiWorkerType.TASK);
    model.setAvailable(Boolean.TRUE);
    return model;
  }
}
