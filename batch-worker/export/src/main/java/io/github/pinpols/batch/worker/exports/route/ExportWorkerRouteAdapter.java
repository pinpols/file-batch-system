package io.github.pinpols.batch.worker.exports.route;

import io.github.pinpols.batch.common.model.WorkerRouteModel;

public interface ExportWorkerRouteAdapter {

  WorkerRouteModel buildDefaultRoute();
}
