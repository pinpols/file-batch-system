package io.github.pinpols.batch.console.domain.ops.application.contract.response;

import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.ops.application.contract.response.OpsResponseFieldReader.stringValue;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
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
      Long maxConcurrent) {
    static WorkerGroupCapacity from(Map<String, Object> row) {
      return new WorkerGroupCapacity(
          stringValue(row, "workerGroup"),
          longValue(row, "totalWorkers"),
          longValue(row, "onlineWorkers"),
          longValue(row, "drainingWorkers"),
          longValue(row, "offlineWorkers"),
          longValue(row, "decommissionedWorkers"),
          longValue(row, "currentLoad"),
          longValue(row, "maxConcurrent"));
    }
  }

  public static ConsoleWorkerConsistencyResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return ConsoleWorkerConsistencyResponse.builder()
        .onlineWorkers(longValue(row, "onlineWorkers"))
        .drainingWorkers(longValue(row, "drainingWorkers"))
        .offlineWorkers(longValue(row, "offlineWorkers"))
        .staleOnlineWorkers(longValue(row, "staleOnlineWorkers"))
        .drainingPastDeadlineWorkers(longValue(row, "drainingPastDeadlineWorkers"))
        .decommissionedWorkersWithActiveTasks(longValue(row, "decommissionedWorkersWithActiveTasks"))
        .invalidCapabilityTags(longValue(row, "invalidCapabilityTags"))
        .workerGroups(mapList(row.get("workerGroups")).stream().map(WorkerGroupCapacity::from).toList())
        .runningInstances(longValue(row, "runningInstances"))
        .healthy(booleanValue(row, "healthy"))
        .build();
  }
}
