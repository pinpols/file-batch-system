package io.github.pinpols.batch.orchestrator.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

  @Insert("""
      insert into batch.file_governance_reconcile_cursor (storage_id, object_prefix)
      values (#{storageId}, #{prefix})
      on conflict (storage_id, object_prefix) do nothing
      """)
  int insertIfAbsent(@Param("storageId") String storageId, @Param("prefix") String prefix);

  @Select("""
      select next_marker, revisit_marker, revisit_budget, revisit_remaining, next_lane, version
      from batch.file_governance_reconcile_cursor
      where storage_id = #{storageId} and object_prefix = #{prefix}
      """)
  @ConstructorArgs({
    @Arg(column = "next_marker", javaType = String.class),
    @Arg(column = "revisit_marker", javaType = String.class),
    @Arg(column = "revisit_budget", javaType = long.class),
    @Arg(column = "revisit_remaining", javaType = long.class),
    @Arg(column = "next_lane", javaType = String.class),
    @Arg(column = "version", javaType = long.class)
  })
  Cursor select(@Param("storageId") String storageId, @Param("prefix") String prefix);

  @Update("""
      update batch.file_governance_reconcile_cursor
      set next_marker = #{nextMarker,jdbcType=VARCHAR},
          revisit_marker = #{revisitMarker,jdbcType=VARCHAR},
          revisit_budget = #{revisitBudget}, revisit_remaining = #{revisitRemaining},
          next_lane = #{nextLane}, version = version + 1,
          updated_at = current_timestamp
      where storage_id = #{storageId} and object_prefix = #{prefix} and version = #{version}
      """)
  int advance(Advance progress);
}
