package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.TenantSchedulerSnapshotRecord;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * batch.tenant_scheduler_snapshot CRUD。原 {@code TenantSchedulerSnapshotRepository}（Spring Data
 * JDBC）已下线，调度器审计快照写读统一由本 Mapper 接管。
 *
 * <p>{@code detail_json} 列是 JSONB；读路径走 {@code ::text + JsonbStringTypeHandler}，写路径走 {@code
 * cast(#{detailJson.value} as jsonb)}。
 */
public interface TenantSchedulerSnapshotMapper {

  /** insert 一行；{@code id} 由 BIGSERIAL 自增并回写到 record (Mapper 级 keyProperty)。 */
  int insert(TenantSchedulerSnapshotRecord record);

  /** 取指定租户最近 {@code limit} 条快照，按 snapshot_at 降序。 */
  List<TenantSchedulerSnapshotRecord> listRecent(
      @Param("tenantId") String tenantId, @Param("limit") int limit);
}
