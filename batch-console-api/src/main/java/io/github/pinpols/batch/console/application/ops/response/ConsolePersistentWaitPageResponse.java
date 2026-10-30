package io.github.pinpols.batch.console.application.ops.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** readiness 与容量准入等待的租户隔离分页投影，不包含任何冻结载荷。 */
public record ConsolePersistentWaitPageResponse(
    List<ConsolePersistentWaitResponse> items, int pageNo, int pageSize, long total) {

  public record ConsolePersistentWaitResponse(
      Long id,
      String waitType,
      String tenantId,
      Long workflowDefinitionId,
      String workflowCode,
      String calendarCode,
      LocalDate bizDate,
      String waitStatus,
      String dependencyJobCodes,
      String reasonCode,
      Integer attemptCount,
      Instant nextAttemptAt,
      Instant deadline,
      Instant scheduledFireTime,
      Instant deferredSince,
      Instant createdAt,
      Instant updatedAt,
      String requestId,
      String traceId,
      String lastErrorCode,
      Long workflowRunId,
      Boolean dryRun) {}
}
