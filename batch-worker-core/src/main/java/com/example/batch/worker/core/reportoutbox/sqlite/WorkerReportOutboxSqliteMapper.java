package com.example.batch.worker.core.reportoutbox.sqlite;

import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxRow;
import com.example.batch.worker.core.reportoutbox.WorkerReportOutboxUpsertParam;
import org.apache.ibatis.annotations.Param;

/**
 * SQLite 专用（独立 {@link org.mybatis.spring.SqlSessionFactoryBean}），勿放入 {@code
 * com.example.batch.worker.core.mapper} 以免被 worker 主 {@code MapperScan} 绑到平台库。
 */
public interface WorkerReportOutboxSqliteMapper {

  int upsert(@Param("p") WorkerReportOutboxUpsertParam p);

  Long pickNextNewId(
      @Param("nowEpochMillis") long nowEpochMillis, @Param("statusNew") String statusNew);

  WorkerReportOutboxRow updateClaimReturning(
      @Param("id") long id,
      @Param("statusPublishing") String statusPublishing,
      @Param("now") long now,
      @Param("statusNew") String statusNew);

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
