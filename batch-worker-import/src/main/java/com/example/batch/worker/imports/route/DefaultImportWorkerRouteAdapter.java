package com.example.batch.worker.imports.route;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.worker.imports.domain.ImportWorkerType;
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
