package com.example.batch.worker.core.mapper;

import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxRow;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 平台 PostgreSQL：{@code batch.worker_report_outbox}（ADR-015）。 */
public interface WorkerReportOutboxPgMapper {

  int upsert(@Param("p") WorkerReportOutboxUpsertParam p);

  List<WorkerReportOutboxRow> claimNextReturning(
      @Param("nowEpochMillis") long nowEpochMillis,
      @Param("statusNew") String statusNew,
      @Param("statusPublishing") String statusPublishing);

  Integer selectAttemptCount(@Param("id") long id);

  int updateGiveUp(
      @Param("id") long id,
      @Param("publishStatus") String publishStatus,
      @Param("attemptCount") int attemptCount,
      @Param("updatedAt") long updatedAt);

  int updateRetry(
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

  int deleteById(@Param("id") long id);

  int giveUpRow(
      @Param("id") long id,
      @Param("publishStatus") String publishStatus,
      @Param("now") long now,
      @Param("maxAttempts") int maxAttempts);
}
