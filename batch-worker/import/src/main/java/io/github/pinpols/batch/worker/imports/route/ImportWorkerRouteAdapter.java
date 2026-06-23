package io.github.pinpols.batch.worker.imports.route;

import io.github.pinpols.batch.common.model.WorkerRouteModel;

public interface ImportWorkerRouteAdapter {

  WorkerRouteModel buildDefaultRoute();
}
