package com.example.batch.worker.dispatchs.route;

import com.example.batch.common.model.WorkerRouteModel;

/** 分发 Worker 路由适配器接口，负责构建默认路由信息。 */
public interface DispatchWorkerRouteAdapter {

  /**
   * 构建分发 Worker 的默认路由模型。
   *
   * @return 路由模型
   */
  WorkerRouteModel buildDefaultRoute();
}
