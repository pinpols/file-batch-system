package com.example.batch.worker.core.mapper;

import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxRow;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 平台 PostgreSQL：{@code batch.worker_report_outbox}（ADR-015）。 */
public interface WorkerReportOutboxPgMapper {

  int upsert(@Param("p") WorkerReportOutboxUpsertParam p);

  /**
   * Citus 路由：{@code tenant_id} 等值使 CTE 内 FOR UPDATE SKIP LOCKED 限定在单分片内合法；UPDATE 同样携带 {@code
   * tenant_id} 保证写路径路由一致。
   */
  List<WorkerReportOutboxRow> claimNextReturning(
      @Param("tenantId") String tenantId,
      @Param("nowEpochMillis") long nowEpochMillis,
      @Param("statusNew") String statusNew,
      @Param("statusPublishing") String statusPublishing);

  Integer selectAttemptCount(@Param("tenantId") String tenantId, @Param("id") long id);

  int updateGiveUp(
      @Param("tenantId") String tenantId,
      @Param("id") long id,
      @Param("publishStatus") String publishStatus,
      @Param("attemptCount") int attemptCount,
      @Param("updatedAt") long updatedAt);

  int updateRetry(
      @Param("tenantId") String tenantId,
      @Param("id") long id,
      @Param("attemptCount") int attemptCount,
      @Param("nextAttemptAt") long nextAttemptAt,
      @Param("updatedAt") long updatedAt,
      @Param("publishStatus") String publishStatus);

  int resetStalePublishing(
      @Param("statusNew") String statusNew,
      @Param("now") long now,
      @Param("statusPublishing") String statusPublishing,
      @Param("cutoff") long cutoff);

  long countByStatus(@Param("publishStatus") String publishStatus);

  long countStalePublishing(
      @Param("statusPublishing") String statusPublishing, @Param("cutoff") long cutoff);

  int deleteById(@Param("tenantId") String tenantId, @Param("id") long id);

  int giveUpRow(
      @Param("tenantId") String tenantId,
      @Param("id") long id,
      @Param("publishStatus") String publishStatus,
      @Param("now") long now,
      @Param("maxAttempts") int maxAttempts);
}
