package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.TenantSchedulerSnapshotEntity;
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

  /** insert 一行；{@code id} 由 BIGSERIAL 自增（不回写：entity 是 record，调用方也无需 id）。 */
  int insert(TenantSchedulerSnapshotEntity record);

  /** 批量 insert：单条 SQL 多 VALUES 行，显著降低 N 租户场景下的 DB round-trip。 */
  int insertBatch(@Param("rows") List<TenantSchedulerSnapshotEntity> rows);

  /** 取指定租户最近 {@code limit} 条快照，按 snapshot_at 降序。 */
  List<TenantSchedulerSnapshotEntity> listRecent(
      @Param("tenantId") String tenantId, @Param("limit") int limit);
}
