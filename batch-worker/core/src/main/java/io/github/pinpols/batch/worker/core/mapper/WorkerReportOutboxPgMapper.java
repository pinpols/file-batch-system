package io.github.pinpols.batch.worker.core.mapper;

import io.github.pinpols.batch.worker.core.param.WorkerReportOutboxPgGiveUpRowParam;
import io.github.pinpols.batch.worker.core.param.WorkerReportOutboxPgUpdateGiveUpParam;
import io.github.pinpols.batch.worker.core.param.WorkerReportOutboxPgUpdateRetryParam;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxRow;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 平台 PostgreSQL：{@code batch.worker_report_outbox}（ADR-015）。 */
public interface WorkerReportOutboxPgMapper {

  int upsert(@Param("p") WorkerReportOutboxUpsertParam p);

  List<WorkerReportOutboxRow> claimNextReturning(
      @Param("nowEpochMillis") long nowEpochMillis,
      @Param("statusNew") String statusNew,
      @Param("statusPublishing") String statusPublishing,
      @Param("claimToken") String claimToken);

  Integer selectAttemptCount(
      @Param("id") long id,
      @Param("claimToken") String claimToken,
      @Param("statusPublishing") String statusPublishing);

  int updateGiveUp(WorkerReportOutboxPgUpdateGiveUpParam parameters);

  int updateRetry(WorkerReportOutboxPgUpdateRetryParam parameters);

  // CPD-OFF: PostgreSQL 与 SQLite 的回报 Outbox Mapper 有意保持相同契约。
  int resetStalePublishing(
      @Param("statusNew") String statusNew,
      @Param("now") long now,
      @Param("statusPublishing") String statusPublishing,
      @Param("cutoff") long cutoff);

  long countByStatus(@Param("publishStatus") String publishStatus);

  long countStalePublishing(
      @Param("statusPublishing") String statusPublishing, @Param("cutoff") long cutoff);

  int deleteById(
      @Param("id") long id,
      @Param("claimToken") String claimToken,
      @Param("statusPublishing") String statusPublishing);

  int giveUpRow(WorkerReportOutboxPgGiveUpRowParam parameters);
  // CPD-ON
}
