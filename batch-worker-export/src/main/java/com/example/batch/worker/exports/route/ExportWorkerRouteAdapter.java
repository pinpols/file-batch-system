package com.example.batch.worker.exports.route;

import com.example.batch.common.model.WorkerRouteModel;

/** 导出 Worker 路由适配器接口，用于构建 Worker 的路由注册信息。 */
public interface ExportWorkerRouteAdapter {

  /**
   * 构建默认路由模型。
   *
   * @return 包含 Worker 类型和可用状态的路由模型
   */
  WorkerRouteModel buildDefaultRoute();
}
