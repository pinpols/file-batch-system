package io.github.pinpols.batch.worker.exports.route;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.worker.exports.domain.ExportWorkerType;
import org.springframework.stereotype.Component;

/** 默认导出 Worker 路由适配器，将 Worker 类型设置为 EXPORT 并标记为可用。 */
@Component
public class DefaultExportWorkerRouteAdapter implements ExportWorkerRouteAdapter {

  @Override
  public WorkerRouteModel buildDefaultRoute() {
    WorkerRouteModel model = new WorkerRouteModel();
    model.setWorkerType(ExportWorkerType.EXPORT);
    model.setAvailable(Boolean.TRUE);
    return model;
  }
}
