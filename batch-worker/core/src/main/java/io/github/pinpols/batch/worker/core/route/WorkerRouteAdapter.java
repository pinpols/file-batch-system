package io.github.pinpols.batch.worker.core.route;

import io.github.pinpols.batch.common.model.WorkerRouteModel;

/**
 * Worker 路由适配器统一契约：构建该 worker 模块的默认路由模型（worker_type + available 等基础元信息）。
 *
 * <p>各 Worker 的默认实现直接 implements 本接口，禁止再次定义同构的 {@code XxxWorkerRouteAdapter} 单实现接口。
 */
public interface WorkerRouteAdapter {

  /** 构建本 worker 模块的默认路由模型。 */
  WorkerRouteModel buildDefaultRoute();
}
