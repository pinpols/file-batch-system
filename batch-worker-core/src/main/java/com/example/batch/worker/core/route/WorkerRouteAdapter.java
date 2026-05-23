package com.example.batch.worker.core.route;

import com.example.batch.common.model.WorkerRouteModel;

/**
 * Worker 路由适配器统一契约：构建该 worker 模块的默认路由模型（worker_type + available 等基础元信息）。
 *
 * <p>历史上 process / dispatch 等模块各自定义同构接口 {@code XxxWorkerRouteAdapter}，下沉到 worker-core 后两侧的
 * Default 实现直接 implements 本接口；其它 worker 模块若需引入也走同一接口，避免再生同构副本。
 */
public interface WorkerRouteAdapter {

  /** 构建本 worker 模块的默认路由模型。 */
  WorkerRouteModel buildDefaultRoute();
}
