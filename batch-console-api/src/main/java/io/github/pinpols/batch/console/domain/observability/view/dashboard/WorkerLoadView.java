package io.github.pinpols.batch.console.domain.observability.view.dashboard;

import java.util.List;

/** Dashboard worker 负载统计。 */
public record WorkerLoadView(
    List<StatusCountView> byStatus,
    List<WorkerGroupStatusCountView> byWorkerGroup,
    List<ActivePartitionView> activePartitionsByWorker) {}
