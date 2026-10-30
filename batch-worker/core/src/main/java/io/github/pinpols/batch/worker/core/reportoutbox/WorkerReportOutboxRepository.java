package io.github.pinpols.batch.worker.core.reportoutbox;

import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.IdGenerator;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.worker.core.domain.TaskExecutionReport;
import io.github.pinpols.batch.worker.core.mapper.WorkerReportOutboxPgMapper;
import io.github.pinpols.batch.worker.core.param.WorkerReportOutboxPgGiveUpRowParam;
import io.github.pinpols.batch.worker.core.param.WorkerReportOutboxPgUpdateGiveUpParam;
import io.github.pinpols.batch.worker.core.param.WorkerReportOutboxPgUpdateRetryParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.WorkerReportOutboxSqliteMapper;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteClaimParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteGiveUpParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteGiveUpRowParam;
import io.github.pinpols.batch.worker.core.reportoutbox.sqlite.param.WorkerReportOutboxSqliteUpdateRetryParam;
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
    String createTableSql = """
        CREATE TABLE IF NOT EXISTS worker_report_outbox (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          tenant_id TEXT NOT NULL,
          task_id INTEGER NOT NULL,
          partition_invocation_id TEXT NOT NULL DEFAULT '',
          trace_id TEXT,
          payload_json TEXT NOT NULL,
          publish_status TEXT NOT NULL,
          attempt_count INTEGER NOT NULL,
          next_attempt_at INTEGER NOT NULL,
          created_at INTEGER NOT NULL,
          updated_at INTEGER NOT NULL,
          claim_token TEXT,
          UNIQUE(tenant_id, task_id, partition_invocation_id)
        )
        """;
    jdbc.execute(createTableSql);
    validateSqliteSchema(jdbc);
    String journalModeSql = "PRAGMA journal_mode=WAL";
    jdbc.execute(journalModeSql);
    String synchronousModeSql = "PRAGMA synchronous=NORMAL";
    jdbc.execute(synchronousModeSql);
    String createPollIndexSql = """
        CREATE INDEX IF NOT EXISTS idx_worker_report_outbox_poll ON worker_report_outbox
          (publish_status, next_attempt_at)
        """;
    jdbc.execute(createPollIndexSql);
  }

  private static void validateSqliteSchema(JdbcTemplate jdbc) {
    String requiredColumnsSql = """
        SELECT count(*) FROM pragma_table_info('worker_report_outbox')
        WHERE name = 'claim_token'
           OR (name = 'partition_invocation_id' AND "notnull" = 1)
        """;
    Integer requiredColumns = jdbc.queryForObject(requiredColumnsSql, Integer.class);
    String indexesSql = """
        SELECT name FROM pragma_index_list('worker_report_outbox') WHERE "unique" = 1
        """;
    List<String> indexes = jdbc.queryForList(indexesSql, String.class);
    List<String> expectedIdentity = List.of("tenant_id", "task_id", "partition_invocation_id");
    List<String> legacyIdentity = List.of("tenant_id", "task_id");
    boolean hasExpectedIdentity = false;
    boolean hasLegacyIdentity = false;
    String indexColumnsSql = "SELECT name FROM pragma_index_info(?) ORDER BY seqno";
    for (String index : indexes) {
      List<String> columns = jdbc.queryForList(indexColumnsSql, String.class, index);
      hasExpectedIdentity |= columns.equals(expectedIdentity);
      hasLegacyIdentity |= columns.equals(legacyIdentity);
    }
    if (EmptyChecks.isNull(requiredColumns)
        || requiredColumns != 2
        || !hasExpectedIdentity
        || hasLegacyIdentity) {
      // 旧运行队列不在ADR-048切换范围内；保留原文件并失败关闭，不能自动清表或搬运。
      throw new IllegalStateException(
          "SQLite report outbox schema is incompatible; preserve the existing file and prepare a fresh deployment store");
    }
  }

  private static void requireClaim(WorkerReportOutboxRow row) {
    if (EmptyChecks.isNull(row) || !Texts.hasText(row.claimToken())) {
      throw new IllegalArgumentException("A current report outbox claim token is required");
    }
  }

  void upsert(TaskExecutionReport report) {
    if (report.getTaskId() == null || !Texts.hasText(report.getTenantId())) {
      throw new IllegalArgumentException(
          "report.taskId and report.tenantId are required for outbox");
    }
    long now = BatchDateTimeSupport.utcEpochMillis();
    String payload = JsonUtils.toJson(report);
    String invocationId = EmptyChecks.isNull(report.getPartitionInvocationId())
        ? ""
        : report.getPartitionInvocationId();
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
    String claimToken = IdGenerator.newTraceId();
    return store.claimNext(nowEpochMillis, claimToken);
  }

  int resetStalePublishing(long updatedAtBeforeExclusive) {
    long now = BatchDateTimeSupport.utcEpochMillis();
    return store.resetStalePublishing(now, updatedAtBeforeExclusive);
  }

  public WorkerReportOutboxStats stats(long staleUpdatedAtBeforeExclusive) {
    return store.stats(staleUpdatedAtBeforeExclusive);
  }

  void delete(WorkerReportOutboxRow row) {
    requireClaim(row);
    store.delete(row);
  }

  /** 返回true仅表示本次领取已实际转为GIVE_UP，供调用方记录放弃指标。 */
  boolean recordFailure(WorkerReportOutboxRow row, long nowEpochMillis, RuntimeException cause) {
    requireClaim(row);
    long id = row.id();
    Integer attemptsNullable = store.selectAttemptCount(row);
    if (attemptsNullable == null) {
      return false;
    }
    int attempts = attemptsNullable;
    int nextAttempts = attempts + 1;
    if (nextAttempts >= props.getMaxPublishAttempts()) {
      int updated = store.updateGiveUp(row, nextAttempts, nowEpochMillis);
      if (updated == 0) {
        log.debug("worker report outbox claim no longer current; ignored give-up: id={}", id);
        return false;
      }
      log.warn(
          "worker report outbox give up after {} attempts: id={}, cause={}",
          nextAttempts,
          id,
          SwallowedExceptionLogger.summary(cause));
      return true;
    } else {
      long backoff = computeBackoffMillis(nextAttempts);
      long jitterMax = Math.max(0L, props.getJitterMillis());
      long jitter = jitterMax == 0
          ? 0L
          : jitterMax == Long.MAX_VALUE
              ? ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE
              : ThreadLocalRandom.current().nextLong(jitterMax + 1);
      long nextAt = saturatedAdd(saturatedAdd(nowEpochMillis, backoff), jitter);
      int updated = store.updateRetry(row, nextAttempts, nextAt, nowEpochMillis);
      if (updated == 0) {
        log.debug("worker report outbox claim no longer current; ignored retry: id={}", id);
        return false;
      }
      log.warn(
          "worker report outbox publish failed: id={}, attempt={}/{}, nextAttemptAt={}, cause={}",
          id,
          nextAttempts,
          props.getMaxPublishAttempts(),
          nextAt,
          SwallowedExceptionLogger.summary(cause));
      return false;
    }
  }

  boolean markGiveUp(WorkerReportOutboxRow row, String reason) {
    requireClaim(row);
    long id = row.id();
    long now = BatchDateTimeSupport.utcEpochMillis();
    int maxAttempts = props.getMaxPublishAttempts();
    int updated = store.giveUpRow(row, now, maxAttempts);
    if (updated == 0) {
      log.debug("worker report outbox claim no longer current; ignored corrupt payload: id={}", id);
      return false;
    }
    log.warn("worker report outbox marked GIVE_UP: id={}, reason={}", id, reason);
    return true;
  }

  private long computeBackoffMillis(int completedAttempts) {
    long initial = Math.max(1L, props.getInitialBackoffMillis());
    long cap = Math.max(initial, props.getMaxBackoffMillis());
    int exponent = Math.max(0, completedAttempts - 1);
    long multiplier = 1L << Math.min(exponent, 30);
    return initial > cap / multiplier ? cap : initial * multiplier;
  }

  private static long saturatedAdd(long value, long nonNegativeIncrement) {
    return value > Long.MAX_VALUE - nonNegativeIncrement
        ? Long.MAX_VALUE
        : value + nonNegativeIncrement;
  }

  private interface WorkerReportOutboxStore {
    void upsert(WorkerReportOutboxUpsertParam param);

    Optional<WorkerReportOutboxRow> claimNext(long nowEpochMillis, String claimToken);

    int resetStalePublishing(long nowEpochMillis, long updatedAtBeforeExclusive);

    WorkerReportOutboxStats stats(long staleUpdatedAtBeforeExclusive);

    void delete(WorkerReportOutboxRow row);

    Integer selectAttemptCount(WorkerReportOutboxRow row);

    int updateGiveUp(WorkerReportOutboxRow row, int nextAttempts, long nowEpochMillis);

    int updateRetry(WorkerReportOutboxRow row, int nextAttempts, long nextAt, long nowEpochMillis);

    int giveUpRow(WorkerReportOutboxRow row, long nowEpochMillis, int maxAttempts);
  }

  private record PostgresqlReportOutboxStore(WorkerReportOutboxPgMapper mapper)
      implements WorkerReportOutboxStore {
    @Override
    public void upsert(WorkerReportOutboxUpsertParam param) {
      mapper.upsert(param);
    }

    @Override
    public Optional<WorkerReportOutboxRow> claimNext(long nowEpochMillis, String claimToken) {
      List<WorkerReportOutboxRow> rows =
          mapper.claimNextReturning(nowEpochMillis, STATUS_NEW, STATUS_PUBLISHING, claimToken);
      return EmptyChecks.isEmpty(rows) ? Optional.empty() : Optional.of(rows.getFirst());
    }

    @Override
    public int resetStalePublishing(long nowEpochMillis, long updatedAtBeforeExclusive) {
      return mapper.resetStalePublishing(
          STATUS_NEW, nowEpochMillis, STATUS_PUBLISHING, updatedAtBeforeExclusive);
    }

    @Override
    public WorkerReportOutboxStats stats(long staleUpdatedAtBeforeExclusive) {
      long newCount = mapper.countByStatus(STATUS_NEW);
      long publishingCount = mapper.countByStatus(STATUS_PUBLISHING);
      long giveUpCount = mapper.countByStatus(STATUS_GIVE_UP);
      long stalePublishingCount =
          mapper.countStalePublishing(STATUS_PUBLISHING, staleUpdatedAtBeforeExclusive);
      return new WorkerReportOutboxStats(
          newCount, publishingCount, giveUpCount, stalePublishingCount);
    }

    @Override
    public void delete(WorkerReportOutboxRow row) {
      mapper.deleteById(row.id(), row.claimToken(), STATUS_PUBLISHING);
    }

    @Override
    public Integer selectAttemptCount(WorkerReportOutboxRow row) {
      return mapper.selectAttemptCount(row.id(), row.claimToken(), STATUS_PUBLISHING);
    }

    @Override
    public int updateGiveUp(WorkerReportOutboxRow row, int nextAttempts, long nowEpochMillis) {
      long id = row.id();
      WorkerReportOutboxPgUpdateGiveUpParam workerReportOutboxPgUpdateGiveUpParam =
          WorkerReportOutboxPgUpdateGiveUpParam.builder()
              .id(id)
              .claimToken(row.claimToken())
              .publishStatus(STATUS_GIVE_UP)
              .attemptCount(nextAttempts)
              .updatedAt(nowEpochMillis)
              .statusPublishing(STATUS_PUBLISHING)
              .build();
      return mapper.updateGiveUp(workerReportOutboxPgUpdateGiveUpParam);
    }

    @Override
    public int updateRetry(
        WorkerReportOutboxRow row, int nextAttempts, long nextAt, long nowEpochMillis) {
      long id = row.id();
      WorkerReportOutboxPgUpdateRetryParam workerReportOutboxPgUpdateRetryParam =
          WorkerReportOutboxPgUpdateRetryParam.builder()
              .id(id)
              .claimToken(row.claimToken())
              .attemptCount(nextAttempts)
              .nextAttemptAt(nextAt)
              .updatedAt(nowEpochMillis)
              .publishStatus(STATUS_NEW)
              .statusPublishing(STATUS_PUBLISHING)
              .build();
      return mapper.updateRetry(workerReportOutboxPgUpdateRetryParam);
    }

    @Override
    public int giveUpRow(WorkerReportOutboxRow row, long nowEpochMillis, int maxAttempts) {
      long id = row.id();
      WorkerReportOutboxPgGiveUpRowParam workerReportOutboxPgGiveUpRowParam =
          WorkerReportOutboxPgGiveUpRowParam.builder()
              .id(id)
              .claimToken(row.claimToken())
              .publishStatus(STATUS_GIVE_UP)
              .now(nowEpochMillis)
              .maxAttempts(maxAttempts)
              .statusPublishing(STATUS_PUBLISHING)
              .build();
      return mapper.giveUpRow(workerReportOutboxPgGiveUpRowParam);
    }
  }

  private record SqliteReportOutboxStore(WorkerReportOutboxSqliteMapper mapper)
      implements WorkerReportOutboxStore {
    @Override
    public void upsert(WorkerReportOutboxUpsertParam param) {
      mapper.upsert(param);
    }

    @Override
    public Optional<WorkerReportOutboxRow> claimNext(long nowEpochMillis, String claimToken) {
      Long id = mapper.pickNextNewId(nowEpochMillis, STATUS_NEW);
      if (EmptyChecks.isNull(id)) {
        return Optional.empty();
      }
      WorkerReportOutboxSqliteClaimParam claim = WorkerReportOutboxSqliteClaimParam.builder()
          .id(id)
          .statusPublishing(STATUS_PUBLISHING)
          .now(nowEpochMillis)
          .statusNew(STATUS_NEW)
          .claimToken(claimToken)
          .build();
      WorkerReportOutboxRow row = mapper.updateClaimReturning(claim);
      return Optional.ofNullable(row);
    }

    @Override
    public int resetStalePublishing(long nowEpochMillis, long updatedAtBeforeExclusive) {
      return mapper.resetStalePublishing(
          STATUS_NEW, nowEpochMillis, STATUS_PUBLISHING, updatedAtBeforeExclusive);
    }

    @Override
    public WorkerReportOutboxStats stats(long staleUpdatedAtBeforeExclusive) {
      long newCount = mapper.countByStatus(STATUS_NEW);
      long publishingCount = mapper.countByStatus(STATUS_PUBLISHING);
      long giveUpCount = mapper.countByStatus(STATUS_GIVE_UP);
      long stalePublishingCount =
          mapper.countStalePublishing(STATUS_PUBLISHING, staleUpdatedAtBeforeExclusive);
      return new WorkerReportOutboxStats(
          newCount, publishingCount, giveUpCount, stalePublishingCount);
    }

    @Override
    public void delete(WorkerReportOutboxRow row) {
      mapper.deleteById(row.id(), row.claimToken(), STATUS_PUBLISHING);
    }

    @Override
    public Integer selectAttemptCount(WorkerReportOutboxRow row) {
      return mapper.selectAttemptCount(row.id(), row.claimToken(), STATUS_PUBLISHING);
    }

    @Override
    public int updateGiveUp(WorkerReportOutboxRow row, int nextAttempts, long nowEpochMillis) {
      long id = row.id();
      WorkerReportOutboxSqliteGiveUpParam parameters = WorkerReportOutboxSqliteGiveUpParam.builder()
          .id(id)
          .publishStatus(STATUS_GIVE_UP)
          .attemptCount(nextAttempts)
          .updatedAt(nowEpochMillis)
          .statusPublishing(STATUS_PUBLISHING)
          .claimToken(row.claimToken())
          .build();
      return mapper.updateGiveUp(parameters);
    }

    @Override
    public int updateRetry(
        WorkerReportOutboxRow row, int nextAttempts, long nextAt, long nowEpochMillis) {
      long id = row.id();
      WorkerReportOutboxSqliteUpdateRetryParam workerReportOutboxSqliteUpdateRetryParam =
          WorkerReportOutboxSqliteUpdateRetryParam.builder()
              .id(id)
              .claimToken(row.claimToken())
              .attemptCount(nextAttempts)
              .nextAttemptAt(nextAt)
              .updatedAt(nowEpochMillis)
              .publishStatus(STATUS_NEW)
              .statusPublishing(STATUS_PUBLISHING)
              .build();
      return mapper.updateRetry(workerReportOutboxSqliteUpdateRetryParam);
    }

    @Override
    public int giveUpRow(WorkerReportOutboxRow row, long nowEpochMillis, int maxAttempts) {
      long id = row.id();
      WorkerReportOutboxSqliteGiveUpRowParam parameters =
          WorkerReportOutboxSqliteGiveUpRowParam.builder()
              .id(id)
              .publishStatus(STATUS_GIVE_UP)
              .now(nowEpochMillis)
              .maxAttempts(maxAttempts)
              .statusPublishing(STATUS_PUBLISHING)
              .claimToken(row.claimToken())
              .build();
      return mapper.giveUpRow(parameters);
    }
  }
}
