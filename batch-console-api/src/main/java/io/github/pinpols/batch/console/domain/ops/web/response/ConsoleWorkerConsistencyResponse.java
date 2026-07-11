package io.github.pinpols.batch.console.domain.ops.web.response;

import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.booleanValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.ops.web.response.OpsResponseFieldReader.stringValue;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** 集群诊断 - Worker 注册一致性。{@code workerGroups} 为 mapper 按组容量投影行。 */
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
   * 单 worker 组容量投影（MyBatis {@code resultType=map}，别名 camelCase；worker_group 可为 null → NON_NULL）。
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
    return new ConsoleWorkerConsistencyResponse(
        longValue(row, "onlineWorkers"),
        longValue(row, "drainingWorkers"),
        longValue(row, "offlineWorkers"),
        longValue(row, "staleOnlineWorkers"),
        longValue(row, "drainingPastDeadlineWorkers"),
        longValue(row, "decommissionedWorkersWithActiveTasks"),
        longValue(row, "invalidCapabilityTags"),
        mapList(row.get("workerGroups")).stream().map(WorkerGroupCapacity::from).toList(),
        longValue(row, "runningInstances"),
        booleanValue(row, "healthy"));
  }
}
