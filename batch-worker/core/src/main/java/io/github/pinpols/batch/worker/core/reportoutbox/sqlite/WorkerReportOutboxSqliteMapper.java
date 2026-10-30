package io.github.pinpols.batch.worker.core.reportoutbox.sqlite;

import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxRow;
import io.github.pinpols.batch.worker.core.reportoutbox.WorkerReportOutboxUpsertParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteClaimParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteGiveUpParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteGiveUpRowParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteUpdateRetryParam;
import org.apache.ibatis.annotations.Param;

/**
 * SQLite 专用（独立 {@link org.mybatis.spring.SqlSessionFactoryBean}），勿放入 {@code
 * io.github.pinpols.batch.worker.core.mapper} 以免被 worker 主 {@code MapperScan} 绑到平台库。
 */
public interface WorkerReportOutboxSqliteMapper {

  int upsert(@Param("p") WorkerReportOutboxUpsertParam p);

  Long pickNextNewId(
      @Param("nowEpochMillis") long nowEpochMillis, @Param("statusNew") String statusNew);

  WorkerReportOutboxRow updateClaimReturning(WorkerReportOutboxSqliteClaimParam parameters);

  Integer selectAttemptCount(
      @Param("id") long id,
      @Param("claimToken") String claimToken,
      @Param("statusPublishing") String statusPublishing);

  int updateGiveUp(WorkerReportOutboxSqliteGiveUpParam parameters);

  int updateRetry(WorkerReportOutboxSqliteUpdateRetryParam parameters);

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

  int giveUpRow(WorkerReportOutboxSqliteGiveUpRowParam parameters);
  // CPD-ON
}
