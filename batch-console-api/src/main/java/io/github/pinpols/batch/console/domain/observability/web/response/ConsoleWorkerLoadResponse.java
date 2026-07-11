package io.github.pinpols.batch.console.domain.observability.web.response;

import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.longValue;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.mapList;
import static io.github.pinpols.batch.console.domain.observability.web.response.ObservabilityResponseFieldReader.stringValue;

import java.util.List;
import java.util.Map;

/** dashboard worker-load 响应：按状态 / 按 worker 组 / 按 worker 活跃分区数（均为固定字段行）。 */
public record ConsoleWorkerLoadResponse(
    List<StatusCountEntry> byStatus,
    List<GroupStatusCountEntry> byWorkerGroup,
    List<WorkerActivePartitionsEntry> activePartitionsByWorker) {

  public record StatusCountEntry(String status, Long count) {
    static StatusCountEntry from(Map<String, Object> row) {
      return new StatusCountEntry(stringValue(row, "status"), longValue(row, "count"));
    }
  }

  public record GroupStatusCountEntry(String workerGroup, String status, Long count) {
    static GroupStatusCountEntry from(Map<String, Object> row) {
      return new GroupStatusCountEntry(
          stringValue(row, "workerGroup"), stringValue(row, "status"), longValue(row, "count"));
    }
  }

  public record WorkerActivePartitionsEntry(String workerCode, Long activePartitions) {
    static WorkerActivePartitionsEntry from(Map<String, Object> row) {
      return new WorkerActivePartitionsEntry(
          stringValue(row, "workerCode"), longValue(row, "activePartitions"));
    }
  }

  public static ConsoleWorkerLoadResponse from(Map<String, Object> row) {
    if (row == null) {
      return null;
    }
    return new ConsoleWorkerLoadResponse(
        mapList(row, "byStatus").stream().map(StatusCountEntry::from).toList(),
        mapList(row, "byWorkerGroup").stream().map(GroupStatusCountEntry::from).toList(),
        mapList(row, "activePartitionsByWorker").stream()
            .map(WorkerActivePartitionsEntry::from)
            .toList());
  }
}
