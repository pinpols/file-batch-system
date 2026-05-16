package com.example.batch.worker.core.reportoutbox;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.common.utils.Texts;
import com.example.batch.worker.core.domain.TaskExecutionReport;
import com.example.batch.worker.core.mapper.WorkerReportOutboxPgMapper;
import com.example.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteMapper;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

@Slf4j
public class WorkerReportOutboxRepository {

  static final String STATUS_NEW = "NEW";
  static final String STATUS_PUBLISHING = "PUBLISHING";
  static final String STATUS_GIVE_UP = "GIVE_UP";

  private final WorkerReportOutboxProperties props;
  private final WorkerReportOutboxDialect dialect;
  private final WorkerReportOutboxPgMapper pgMapper;
  private final WorkerReportOutboxSqliteMapper sqliteMapper;

  public WorkerReportOutboxRepository(
      WorkerReportOutboxProperties props,
      WorkerReportOutboxDialect dialect,
      WorkerReportOutboxPgMapper pgMapper,
      WorkerReportOutboxSqliteMapper sqliteMapper,
      JdbcTemplate sqliteDdlJdbcTemplate) {
    this.props = props;
    this.dialect = dialect;
    if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
      if (pgMapper == null) {
        throw new IllegalArgumentException("WorkerReportOutboxPgMapper required for PLATFORM_PG");
      }
      this.pgMapper = pgMapper;
      this.sqliteMapper = null;
    } else {
      if (sqliteMapper == null || sqliteDdlJdbcTemplate == null) {
        throw new IllegalArgumentException(
            "WorkerReportOutboxSqliteMapper + JdbcTemplate required for SQLITE");
      }
      this.pgMapper = null;
      this.sqliteMapper = sqliteMapper;
      initializeSqliteSchema(sqliteDdlJdbcTemplate);
    }
  }

  private static void initializeSqliteSchema(JdbcTemplate jdbc) {
    jdbc.execute("PRAGMA journal_mode=WAL");
    jdbc.execute("PRAGMA synchronous=NORMAL");
    jdbc.execute(
        """
        CREATE TABLE IF NOT EXISTS worker_report_outbox (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          tenant_id TEXT NOT NULL,
          task_id INTEGER NOT NULL,
          partition_invocation_id TEXT,
          trace_id TEXT,
          payload_json TEXT NOT NULL,
          publish_status TEXT NOT NULL,
          attempt_count INTEGER NOT NULL,
          next_attempt_at INTEGER NOT NULL,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          UNIQUE(tenant_id, task_id)
        )
        """);
    jdbc.execute(
        "CREATE INDEX IF NOT EXISTS idx_worker_report_outbox_poll ON worker_report_outbox"
            + " (publish_status, next_attempt_at)");
  }

  void upsert(TaskExecutionReport report) {
    if (report.getTaskId() == null || !Texts.hasText(report.getTenantId())) {
      throw new IllegalArgumentException(
          "report.taskId and report.tenantId are required for outbox");
    }
    long now = BatchDateTimeSupport.utcEpochMillis();
    String payload = JsonUtils.toJson(report);
    String invocationId =
        report.getPartitionInvocationId() == null ? null : report.getPartitionInvocationId();
    String traceId = report.getTraceId() == null ? null : report.getTraceId();
    WorkerReportOutboxUpsertParam p =
        new WorkerReportOutboxUpsertParam(
            report.getTenantId(),
            report.getTaskId(),
            invocationId,
            traceId,
            payload,
            STATUS_NEW,
            now,
            now,
            now);
    if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
      pgMapper.upsert(p);
    } else {
      sqliteMapper.upsert(p);
    }
  }

  TaskExecutionReport deserializePayload(String payloadJson) {
    return JsonUtils.fromJson(payloadJson, TaskExecutionReport.class);
  }

  /** 抢占一行（NEW→PUBLISHING）。须在短事务内调用（见 {@link WorkerReportOutboxPollClaimer}）。 */
  Optional<WorkerReportOutboxRow> claimNext(long nowEpochMillis) {
    if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
      List<WorkerReportOutboxRow> rows =
          pgMapper.claimNextReturning(nowEpochMillis, STATUS_NEW, STATUS_PUBLISHING);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }
    Long id = sqliteMapper.pickNextNewId(nowEpochMillis, STATUS_NEW);
    if (id == null) {
      return Optional.empty();
    }
    WorkerReportOutboxRow row =
        sqliteMapper.updateClaimReturning(id, STATUS_PUBLISHING, nowEpochMillis, STATUS_NEW);
    return Optional.ofNullable(row);
  }

  int resetStalePublishing(long updatedAtBeforeExclusive) {
    long now = BatchDateTimeSupport.utcEpochMillis();
    if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
      return pgMapper.resetStalePublishing(
          STATUS_NEW, now, STATUS_PUBLISHING, updatedAtBeforeExclusive);
    }
    return sqliteMapper.resetStalePublishing(
        STATUS_NEW, now, STATUS_PUBLISHING, updatedAtBeforeExclusive);
  }

  public WorkerReportOutboxStats stats(long staleUpdatedAtBeforeExclusive) {
    if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
      return new WorkerReportOutboxStats(
          pgMapper.countByStatus(STATUS_NEW),
          pgMapper.countByStatus(STATUS_PUBLISHING),
          pgMapper.countByStatus(STATUS_GIVE_UP),
          pgMapper.countStalePublishing(STATUS_PUBLISHING, staleUpdatedAtBeforeExclusive));
    }
    return new WorkerReportOutboxStats(
        sqliteMapper.countByStatus(STATUS_NEW),
        sqliteMapper.countByStatus(STATUS_PUBLISHING),
        sqliteMapper.countByStatus(STATUS_GIVE_UP),
        sqliteMapper.countStalePublishing(STATUS_PUBLISHING, staleUpdatedAtBeforeExclusive));
  }

  void delete(long id) {
    if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
      pgMapper.deleteById(id);
    } else {
      sqliteMapper.deleteById(id);
    }
  }

  void recordFailure(long id, long nowEpochMillis, RuntimeException cause) {
    Integer attemptsNullable =
        dialect == WorkerReportOutboxDialect.POSTGRESQL
            ? pgMapper.selectAttemptCount(id)
            : sqliteMapper.selectAttemptCount(id);
    if (attemptsNullable == null) {
      return;
    }
    int attempts = attemptsNullable;
    int nextAttempts = attempts + 1;
    if (nextAttempts >= props.getMaxPublishAttempts()) {
      if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
        pgMapper.updateGiveUp(id, STATUS_GIVE_UP, nextAttempts, nowEpochMillis);
      } else {
        sqliteMapper.updateGiveUp(id, STATUS_GIVE_UP, nextAttempts, nowEpochMillis);
      }
      log.warn(
          "worker report outbox give up after {} attempts: id={}, cause={}",
          nextAttempts,
          id,
          cause.toString());
    } else {
      long backoff = computeBackoffMillis(nextAttempts);
      long jitterMax = Math.max(0L, props.getJitterMillis());
      long jitter = jitterMax == 0 ? 0L : ThreadLocalRandom.current().nextLong(0, jitterMax + 1);
      long nextAt = nowEpochMillis + backoff + jitter;
      if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
        pgMapper.updateRetry(id, nextAttempts, nextAt, nowEpochMillis, STATUS_NEW);
      } else {
        sqliteMapper.updateRetry(id, nextAttempts, nextAt, nowEpochMillis, STATUS_NEW);
      }
      log.warn(
          "worker report outbox publish failed: id={}, attempt={}/{}, nextAttemptAt={}, cause={}",
          id,
          nextAttempts,
          props.getMaxPublishAttempts(),
          nextAt,
          cause.toString());
    }
  }

  void markGiveUp(long id, String reason) {
    long now = BatchDateTimeSupport.utcEpochMillis();
    int maxAttempts = props.getMaxPublishAttempts();
    if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
      pgMapper.giveUpRow(id, STATUS_GIVE_UP, now, maxAttempts);
    } else {
      sqliteMapper.giveUpRow(id, STATUS_GIVE_UP, now, maxAttempts);
    }
    log.warn("worker report outbox marked GIVE_UP: id={}, reason={}", id, reason);
  }

  private long computeBackoffMillis(int completedAttempts) {
    long initial = Math.max(1L, props.getInitialBackoffMillis());
    long cap = Math.max(initial, props.getMaxBackoffMillis());
    int exponent = Math.max(0, completedAttempts - 1);
    long multiplier = 1L << Math.min(exponent, 30);
    long scaled = initial * multiplier;
    return Math.min(cap, scaled);
  }
}
