package io.github.pinpols.batch.worker.dispatchs.route;

import org.springframework.stereotype.Component;

import io.github.pinpols.batch.common.model.WorkerRouteModel;
import io.github.pinpols.batch.worker.core.route.WorkerRouteAdapter;
import io.github.pinpols.batch.worker.dispatchs.domain.DispatchWorkerType;

/** 分发 Worker 默认路由适配器实现。 */
@Component
public class DefaultDispatchWorkerRouteAdapter implements WorkerRouteAdapter {

    @Override
    public WorkerRouteModel buildDefaultRoute() {
        WorkerRouteModel model = new WorkerRouteModel();
        model.setWorkerType(DispatchWorkerType.DISPATCH);
        model.setAvailable(Boolean.TRUE);
        return model;
    }
}
