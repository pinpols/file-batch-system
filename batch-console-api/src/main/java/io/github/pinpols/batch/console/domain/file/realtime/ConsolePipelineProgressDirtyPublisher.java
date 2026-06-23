package io.github.pinpols.batch.console.domain.file.realtime;

import io.github.pinpols.batch.console.domain.file.web.response.ConsolePipelineProgressDirtyEventResponse;
import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeDomainEventPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 低频扫描 {@code batch.pipeline_progress.updated_at}，发布 pipeline-progress dirty 事件。
 *
 * <p>console-api 未启用全局 {@code @EnableScheduling}，因此沿用自管理调度线程。事件按 pipeline 节流；前端仍需用查询端点拉取真实快照。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "batch.console.pipeline-progress-dirty",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class ConsolePipelineProgressDirtyPublisher {

  private static final String STREAM = "pipeline-progress";
  private static final String EVENT_TYPE = "pipeline-progress-dirty";
  private static final String REASON_STEP_PROGRESS = "STEP_PROGRESS";
  private static final int MAX_TRACKED_KEYS = 10_000;
  private static final String DIRTY_QUERY =
      "select pr.tenant_id,"
          + " pr.pipeline_instance_id,"
          + " pi.related_job_instance_id,"
          + " max(pr.updated_at) as updated_at"
          + " from batch.pipeline_progress pr"
          + " join batch.pipeline_instance pi"
          + "   on pi.tenant_id = pr.tenant_id"
          + "  and pi.id = pr.pipeline_instance_id"
          + " where pr.updated_at >= :since"
          + " group by pr.tenant_id, pr.pipeline_instance_id, pi.related_job_instance_id"
          + " order by max(pr.updated_at) asc"
          + " limit :limit";

  private final NamedParameterJdbcTemplate jdbc;
  private final ConsoleRealtimeDomainEventPublisher eventPublisher;

  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private final Map<String, Instant> lastPublishedUpdateByPipeline = new ConcurrentHashMap<>();
  private final Map<String, Instant> lastEmittedAtByPipeline = new ConcurrentHashMap<>();

  private ScheduledExecutorService scheduler;
  private volatile Instant lastSeen = Instant.now().minusSeconds(30);

  @Value("${batch.console.pipeline-progress-dirty.interval-millis:5000}")
  private long intervalMillis;

  @Value("${batch.console.pipeline-progress-dirty.initial-delay-millis:10000}")
  private long initialDelayMillis;

  @Value("${batch.console.pipeline-progress-dirty.lookback-overlap-millis:2000}")
  private long lookbackOverlapMillis;

  @Value("${batch.console.pipeline-progress-dirty.throttle-millis:10000}")
  private long throttleMillis;

  @Value("${batch.console.pipeline-progress-dirty.batch-size:500}")
  private int batchSize;

  @PostConstruct
  void start() {
    intervalMillis = Math.max(1_000L, intervalMillis);
    initialDelayMillis = Math.max(0L, initialDelayMillis);
    lookbackOverlapMillis = Math.max(0L, lookbackOverlapMillis);
    throttleMillis = Math.max(intervalMillis, throttleMillis);
    batchSize = Math.max(1, Math.min(batchSize, 2_000));

    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "pipeline-progress-dirty-publisher");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::pollSafely, initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
    log.info(
        "pipeline progress dirty publisher started: intervalMs={}, throttleMs={}, batchSize={}",
        intervalMillis,
        throttleMillis,
        batchSize);
  }

  @EventListener(ContextClosedEvent.class)
  void stopOnContextClosed(ContextClosedEvent event) {
    stopScheduler("context-closed");
  }

  @PreDestroy
  void stop() {
    stopScheduler("pre-destroy");
  }

  private void stopScheduler(String source) {
    if (!stopping.compareAndSet(false, true)) {
      return;
    }
    if (scheduler != null) {
      log.info("pipeline progress dirty publisher stopping: source={}", source);
      scheduler.shutdownNow();
    }
  }

  void pollSafely() {
    if (stopping.get()) {
      return;
    }
    try {
      pollOnce();
    } catch (RuntimeException ex) {
      if (stopping.get()) {
        log.info("pipeline progress dirty poll skipped during shutdown: {}", ex.getMessage());
        return;
      }
      log.warn("pipeline progress dirty poll failed: {}", ex.getMessage(), ex);
    }
  }

  void pollOnce() {
    Instant since = lastSeen.minusMillis(lookbackOverlapMillis);
    List<DirtyProgressRow> rows =
        jdbc.query(
            DIRTY_QUERY,
            new MapSqlParameterSource()
                .addValue("since", Timestamp.from(since))
                .addValue("limit", batchSize),
            this::mapDirtyProgressRow);
    if (rows.isEmpty()) {
      return;
    }

    Instant maxSeen = lastSeen;
    Instant now = Instant.now();
    for (DirtyProgressRow row : rows) {
      if (row.updatedAt().isAfter(maxSeen)) {
        maxSeen = row.updatedAt();
      }
      if (shouldPublish(row, now)) {
        publish(row);
      }
    }
    lastSeen = maxSeen;
    pruneTrackedKeysIfNeeded();
  }

  private DirtyProgressRow mapDirtyProgressRow(ResultSet rs, int rowNum) throws SQLException {
    return new DirtyProgressRow(
        rs.getString("tenant_id"),
        rs.getLong("pipeline_instance_id"),
        nullableLong(rs, "related_job_instance_id"),
        rs.getTimestamp("updated_at").toInstant());
  }

  private boolean shouldPublish(DirtyProgressRow row, Instant now) {
    String key = row.tenantId() + "|" + row.pipelineInstanceId();
    Instant lastPublishedUpdate = lastPublishedUpdateByPipeline.get(key);
    if (lastPublishedUpdate != null && !row.updatedAt().isAfter(lastPublishedUpdate)) {
      return false;
    }
    Instant lastEmittedAt = lastEmittedAtByPipeline.get(key);
    if (lastEmittedAt != null && Duration.between(lastEmittedAt, now).toMillis() < throttleMillis) {
      return false;
    }
    lastPublishedUpdateByPipeline.put(key, row.updatedAt());
    lastEmittedAtByPipeline.put(key, now);
    return true;
  }

  private void publish(DirtyProgressRow row) {
    ConsolePipelineProgressDirtyEventResponse payload =
        new ConsolePipelineProgressDirtyEventResponse(
            row.tenantId(),
            row.pipelineInstanceId(),
            row.jobInstanceId(),
            REASON_STEP_PROGRESS,
            row.updatedAt().toEpochMilli(),
            row.updatedAt());
    eventPublisher.publishChanged(row.tenantId(), STREAM, EVENT_TYPE, payload);
  }

  private void pruneTrackedKeysIfNeeded() {
    if (lastPublishedUpdateByPipeline.size() <= MAX_TRACKED_KEYS
        && lastEmittedAtByPipeline.size() <= MAX_TRACKED_KEYS) {
      return;
    }
    lastPublishedUpdateByPipeline.clear();
    lastEmittedAtByPipeline.clear();
  }

  private static Long nullableLong(ResultSet rs, String column) throws SQLException {
    long value = rs.getLong(column);
    return rs.wasNull() ? null : value;
  }

  private record DirtyProgressRow(
      String tenantId, Long pipelineInstanceId, Long jobInstanceId, Instant updatedAt) {}
}
