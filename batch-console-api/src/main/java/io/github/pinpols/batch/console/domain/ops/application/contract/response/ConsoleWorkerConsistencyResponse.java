package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import lombok.Builder;

/** 集群诊断 - Worker 注册一致性。{@code workerGroups} 为 mapper 按组容量投影行。 */
@Builder
public record ConsoleWorkerConsistencyResponse(
    Long onlineWorkers,
    Long drainingWorkers,
    Long offlineWorkers,
    Long staleOnlineWorkers,
    Long drainingPastDeadlineWorkers,
    Long decommissionedWorkersWithActiveTasks,
    Long invalidCapabilityTags,
    List<WorkerGroupCapacity> workerGroups,
    Long runningInstances,
    Boolean healthy) {

  /**
   * 单 worker 组容量响应行；映射自具名查询视图，可空组名不输出。
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record WorkerGroupCapacity(
      String workerGroup,
      Long totalWorkers,
      Long onlineWorkers,
      Long drainingWorkers,
      Long offlineWorkers,
      Long decommissionedWorkers,
      Long currentLoad,
      Long maxConcurrent) {}
}
