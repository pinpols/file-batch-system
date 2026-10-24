package io.github.pinpols.batch.console.domain.ops.view.cluster;

/** Worker 组容量统计的固定列投影。 */
public record WorkerGroupCapacityView(
    String workerGroup,
    Long totalWorkers,
    Long onlineWorkers,
    Long drainingWorkers,
    Long offlineWorkers,
    Long decommissionedWorkers,
    Long currentLoad,
    Long maxConcurrent) {}
