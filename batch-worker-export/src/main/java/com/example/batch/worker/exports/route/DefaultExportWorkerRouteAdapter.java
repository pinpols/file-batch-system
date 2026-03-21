package com.example.batch.worker.exports.route;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.worker.exports.domain.ExportWorkerType;
import org.springframework.stereotype.Component;

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
