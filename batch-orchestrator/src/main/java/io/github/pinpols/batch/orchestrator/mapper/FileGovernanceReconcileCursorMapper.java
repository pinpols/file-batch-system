package io.github.pinpols.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Param;

/** 对象治理对账的共享分页进度。 */
public interface FileGovernanceReconcileCursorMapper {

  record Cursor(
      String nextMarker,
      String revisitMarker,
      long revisitBudget,
      long revisitRemaining,
      String nextLane,
      long version) {}

  record Advance(
      String storageId,
      String prefix,
      long version,
      String nextMarker,
      String revisitMarker,
      long revisitBudget,
      long revisitRemaining,
      String nextLane) {}

  int insertIfAbsent(@Param("storageId") String storageId, @Param("prefix") String prefix);

  Cursor select(@Param("storageId") String storageId, @Param("prefix") String prefix);

  int advance(Advance progress);
}
