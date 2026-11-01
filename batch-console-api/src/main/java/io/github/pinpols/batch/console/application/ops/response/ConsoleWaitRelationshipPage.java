package io.github.pinpols.batch.console.application.ops.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 逐页实时读取结果，不构成跨页一致性快照。 */
public record ConsoleWaitRelationshipPage(
    List<Item> items, boolean hasMore, String nextCursorType, String nextCursorId) {
  public record Item(
      String id,
      String waitType,
      String tenantId,
      String subjectCode,
      LocalDate bizDate,
      String waitStatus,
      String dependencySnapshot,
      boolean dependencySnapshotValid,
      String reason,
      Instant updatedAt,
      String requestId) {}
}
