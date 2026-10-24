package io.github.pinpols.batch.worker.core.infrastructure;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Map;

/** Worker 启动业务审计扩展点。实现必须只读、轻量，不能修复数据或阻塞业务启动。 */
public interface WorkerStartupAuditContributor {

  String name();

  WorkerStartupAuditResult audit();

  record WorkerStartupAuditResult(boolean healthy, Map<String, Object> details) {

    public static WorkerStartupAuditResult healthy(Map<String, Object> details) {
      Map<String, Object> immutableDetails = EmptyChecks.isNull(details) ? Map.of() : Map.copyOf(details);
      return new WorkerStartupAuditResult(true, immutableDetails);
    }

    public static WorkerStartupAuditResult unhealthy(Map<String, Object> details) {
      Map<String, Object> immutableDetails = EmptyChecks.isNull(details) ? Map.of() : Map.copyOf(details);
      return new WorkerStartupAuditResult(false, immutableDetails);
    }
  }
}
