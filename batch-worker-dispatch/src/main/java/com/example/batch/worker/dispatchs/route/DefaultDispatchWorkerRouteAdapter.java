package com.example.batch.worker.dispatchs.route;

import com.example.batch.common.model.WorkerRouteModel;
import com.example.batch.worker.dispatchs.domain.DispatchWorkerType;
import org.springframework.stereotype.Component;

@Component
public class DefaultDispatchWorkerRouteAdapter implements DispatchWorkerRouteAdapter {

    @Override
    public WorkerRouteModel buildDefaultRoute() {
        WorkerRouteModel model = new WorkerRouteModel();
        model.setWorkerType(DispatchWorkerType.DISPATCH);
        model.setAvailable(Boolean.TRUE);
        return model;
    }
}
