package com.example.batch.worker.core.infrastructure;

import java.util.Map;

/** Worker 启动业务审计扩展点。实现必须只读、轻量，不能修复数据或阻塞业务启动。 */
public interface WorkerStartupAuditContributor {

  String name();

  WorkerStartupAuditResult audit();

  record WorkerStartupAuditResult(boolean healthy, Map<String, Object> details) {

    public static WorkerStartupAuditResult healthy(Map<String, Object> details) {
      return new WorkerStartupAuditResult(true, details == null ? Map.of() : Map.copyOf(details));
    }

    public static WorkerStartupAuditResult unhealthy(Map<String, Object> details) {
      return new WorkerStartupAuditResult(false, details == null ? Map.of() : Map.copyOf(details));
    }
  }
}
