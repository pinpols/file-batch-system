package io.github.pinpols.batch.worker.core.reportoutbox;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.mapper.WorkerReportOutboxPgMapper;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteMapper;
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
  private final WorkerReportOutboxStore store;

  public WorkerReportOutboxRepository(
      WorkerReportOutboxProperties props,
      WorkerReportOutboxDialect dialect,
      WorkerReportOutboxPgMapper pgMapper,
      WorkerReportOutboxSqliteMapper sqliteMapper,
      JdbcTemplate sqliteDdlJdbcTemplate) {
    this.props = props;
    if (dialect == WorkerReportOutboxDialect.POSTGRESQL) {
      if (pgMapper == null) {
        throw new IllegalArgumentException("WorkerReportOutboxPgMapper required for PLATFORM_PG");
      }
      this.store = new PostgresqlReportOutboxStore(pgMapper);
    } else {
      if (sqliteMapper == null || sqliteDdlJdbcTemplate == null) {
        throw new IllegalArgumentException(
            "WorkerReportOutboxSqliteMapper + JdbcTemplate required for SQLITE");
      }
      initializeSqliteSchema(sqliteDdlJdbcTemplate);
      this.store = new SqliteReportOutboxStore(sqliteMapper);
    }
  }

  private static void initializeSqliteSchema(JdbcTemplate jdbc) {
    jdbc.execute("PRAGMA journal_mode=WAL");
    jdbc.execute("PRAGMA synchronous=NORMAL");
    jdbc.execute("""
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
    jdbc.execute("CREATE INDEX IF NOT EXISTS idx_worker_report_outbox_poll ON worker_report_outbox"
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
    WorkerReportOutboxUpsertParam p = new WorkerReportOutboxUpsertParam(
        report.getTenantId(),
        report.getTaskId(),
        invocationId,
        traceId,
        payload,
        STATUS_NEW,
        now,
        now,
        now);
    store.upsert(p);
  }

  TaskExecutionReport deserializePayload(String payloadJson) {
    return JsonUtils.fromJson(payloadJson, TaskExecutionReport.class);
  }

  /** 抢占一行（NEW→PUBLISHING）。须在短事务内调用（见 {@link WorkerReportOutboxPollClaimer}）。 */
  Optional<WorkerReportOutboxRow> claimNext(long nowEpochMillis) {
    return store.claimNext(nowEpochMillis);
  }

  int resetStalePublishing(long updatedAtBeforeExclusive) {
    long now = BatchDateTimeSupport.utcEpochMillis();
    return store.resetStalePublishing(now, updatedAtBeforeExclusive);
  }

  public WorkerReportOutboxStats stats(long staleUpdatedAtBeforeExclusive) {
    return store.stats(staleUpdatedAtBeforeExclusive);
  }

  void delete(long id) {
    store.delete(id);
  }

  void recordFailure(long id, long nowEpochMillis, RuntimeException cause) {
    Integer attemptsNullable = store.selectAttemptCount(id);
    if (attemptsNullable == null) {
      return;
    }
    int attempts = attemptsNullable;
    int nextAttempts = attempts + 1;
    if (nextAttempts >= props.getMaxPublishAttempts()) {
      int updated = store.updateGiveUp(id, nextAttempts, nowEpochMillis);
      if (updated == 0) {
        log.warn(
            "Worker report Outbox updateGiveUp affected 0 rows; another instance took over the event: id={}",
            id);
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
      int updated = store.updateRetry(id, nextAttempts, nextAt, nowEpochMillis);
      if (updated == 0) {
        log.warn(
            "Worker report Outbox updateRetry affected 0 rows; another instance took over the event: id={}",
            id);
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
    int updated = store.giveUpRow(id, now, maxAttempts);
    if (updated == 0) {
      log.warn(
          "Worker report Outbox giveUpRow affected 0 rows; another instance took over the event: id={}",
          id);
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

  private interface WorkerReportOutboxStore {
    void upsert(WorkerReportOutboxUpsertParam param);

    Optional<WorkerReportOutboxRow> claimNext(long nowEpochMillis);

    int resetStalePublishing(long nowEpochMillis, long updatedAtBeforeExclusive);

    WorkerReportOutboxStats stats(long staleUpdatedAtBeforeExclusive);

    void delete(long id);

    Integer selectAttemptCount(long id);

    int updateGiveUp(long id, int nextAttempts, long nowEpochMillis);

    int updateRetry(long id, int nextAttempts, long nextAt, long nowEpochMillis);

    int giveUpRow(long id, long nowEpochMillis, int maxAttempts);
  }

  private record PostgresqlReportOutboxStore(WorkerReportOutboxPgMapper mapper)
      implements WorkerReportOutboxStore {
    @Override
    public void upsert(WorkerReportOutboxUpsertParam param) {
      mapper.upsert(param);
    }

    @Override
    public Optional<WorkerReportOutboxRow> claimNext(long nowEpochMillis) {
      List<WorkerReportOutboxRow> rows =
          mapper.claimNextReturning(nowEpochMillis, STATUS_NEW, STATUS_PUBLISHING);
      return EmptyChecks.isEmpty(rows) ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public int resetStalePublishing(long nowEpochMillis, long updatedAtBeforeExclusive) {
      return mapper.resetStalePublishing(
          STATUS_NEW, nowEpochMillis, STATUS_PUBLISHING, updatedAtBeforeExclusive);
    }

    @Override
    public WorkerReportOutboxStats stats(long staleUpdatedAtBeforeExclusive) {
      return new WorkerReportOutboxStats(
          mapper.countByStatus(STATUS_NEW),
          mapper.countByStatus(STATUS_PUBLISHING),
          mapper.countByStatus(STATUS_GIVE_UP),
          mapper.countStalePublishing(STATUS_PUBLISHING, staleUpdatedAtBeforeExclusive));
    }

    @Override
    public void delete(long id) {
      mapper.deleteById(id);
    }

    @Override
    public Integer selectAttemptCount(long id) {
      return mapper.selectAttemptCount(id);
    }

    @Override
    public int updateGiveUp(long id, int nextAttempts, long nowEpochMillis) {
      return mapper.updateGiveUp(
          id, STATUS_GIVE_UP, nextAttempts, nowEpochMillis, STATUS_PUBLISHING);
    }

    @Override
    public int updateRetry(long id, int nextAttempts, long nextAt, long nowEpochMillis) {
      return mapper.updateRetry(
          id, nextAttempts, nextAt, nowEpochMillis, STATUS_NEW, STATUS_PUBLISHING);
    }

    @Override
    public int giveUpRow(long id, long nowEpochMillis, int maxAttempts) {
      return mapper.giveUpRow(id, STATUS_GIVE_UP, nowEpochMillis, maxAttempts, STATUS_PUBLISHING);
    }
  }

  private record SqliteReportOutboxStore(WorkerReportOutboxSqliteMapper mapper)
      implements WorkerReportOutboxStore {
    @Override
    public void upsert(WorkerReportOutboxUpsertParam param) {
      mapper.upsert(param);
    }

    @Override
    public Optional<WorkerReportOutboxRow> claimNext(long nowEpochMillis) {
      Long id = mapper.pickNextNewId(nowEpochMillis, STATUS_NEW);
      if (EmptyChecks.isNull(id)) {
        return Optional.empty();
      }
      WorkerReportOutboxRow row =
          mapper.updateClaimReturning(id, STATUS_PUBLISHING, nowEpochMillis, STATUS_NEW);
      return Optional.ofNullable(row);
    }

    @Override
    public int resetStalePublishing(long nowEpochMillis, long updatedAtBeforeExclusive) {
      return mapper.resetStalePublishing(
          STATUS_NEW, nowEpochMillis, STATUS_PUBLISHING, updatedAtBeforeExclusive);
    }

    @Override
    public WorkerReportOutboxStats stats(long staleUpdatedAtBeforeExclusive) {
      return new WorkerReportOutboxStats(
          mapper.countByStatus(STATUS_NEW),
          mapper.countByStatus(STATUS_PUBLISHING),
          mapper.countByStatus(STATUS_GIVE_UP),
          mapper.countStalePublishing(STATUS_PUBLISHING, staleUpdatedAtBeforeExclusive));
    }

    @Override
    public void delete(long id) {
      mapper.deleteById(id);
    }

    @Override
    public Integer selectAttemptCount(long id) {
      return mapper.selectAttemptCount(id);
    }

    @Override
    public int updateGiveUp(long id, int nextAttempts, long nowEpochMillis) {
      return mapper.updateGiveUp(id, STATUS_GIVE_UP, nextAttempts, nowEpochMillis);
    }

    @Override
    public int updateRetry(long id, int nextAttempts, long nextAt, long nowEpochMillis) {
      return mapper.updateRetry(id, nextAttempts, nextAt, nowEpochMillis, STATUS_NEW);
    }

    @Override
    public int giveUpRow(long id, long nowEpochMillis, int maxAttempts) {
      return mapper.giveUpRow(id, STATUS_GIVE_UP, nowEpochMillis, maxAttempts);
    }
  }
}
