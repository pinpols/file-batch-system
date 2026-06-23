package io.github.pinpols.batch.worker.imports.route;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.worker.imports.domain.ImportWorkerType;
import org.springframework.stereotype.Component;

@Component
public class DefaultImportWorkerRouteAdapter implements ImportWorkerRouteAdapter {

  @Override
  public WorkerRouteModel buildDefaultRoute() {
    WorkerRouteModel model = new WorkerRouteModel();
    model.setWorkerType(ImportWorkerType.IMPORT);
    model.setAvailable(Boolean.TRUE);
    return model;
  }
}
