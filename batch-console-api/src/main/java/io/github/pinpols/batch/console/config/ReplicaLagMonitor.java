package io.github.pinpols.batch.console.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

/**
 * Replica WAL replay lag 监控器：定时在 primary 上查 {@code pg_stat_replication.replay_lag}， 暴露 Prometheus
 * gauge {@code batch.console.replica.replay_lag_seconds}。
 *
 * <p>动机：{@link ReadReplicaRoutingDataSource} 只有"连接成功 / 失败 / quarantine"几个粗事件指标，
 * 但流复制可能在连接层正常的同时延迟到秒级（写流量峰值、checkpoint 阻塞、网络抖动），导致 console 读到流复制 lag 之前的旧数据，
 * 业务出现"刚保存就看不到"的不一致。replay_lag 提供精确的秒级延迟观测，让运维能在阈值告警而不是等用户投诉。
 *
 * <p>采集策略：
 *
 * <ul>
 *   <li>每 {@code batch.console.replica.lag-monitor-interval-millis}（默认 30s）跑一次
 *   <li>SQL 必须在 <b>primary</b> 上跑：{@code pg_stat_replication} 视图只在主库有数据；replica 看不到
 *   <li>取 streaming 状态下所有 replica 的 max(replay_lag)；多 replica 部署取最差值
 *   <li>失败/无 streaming replica 时 gauge 设 -1（运维侧 Prometheus alert 应同时检查 -1 与超阈值）
 * </ul>
 *
 * <p>权限：所属 DB user 需要被 grant {@code pg_monitor} role 或 {@code pg_read_all_stats} role。 缺权限时此 gauge
 * 持续为 -1（带 warn 日志），不影响主业务路径。
 *
 * <p>调度：本类自管理 {@link ScheduledExecutorService} 而非依赖 Spring {@code @EnableScheduling}—— console-api
 * 主类未启用全局 scheduling，避免引入不相关的副作用。
 */
@Slf4j
public class ReplicaLagMonitor {

  private static final String METRIC_NAME = "batch.console.replica.replay_lag_seconds";
  private static final String METRIC_REPLICAS = "batch.console.replica.streaming_count";
  // 同时取 max(replay_lag) 和 streaming replica 数量。replay_lag NULL = 0,replica 数 = 0 视为「全断」。
  private static final String LAG_QUERY =
      "SELECT COALESCE(EXTRACT(EPOCH FROM MAX(replay_lag)), 0)::double precision AS lag_seconds,"
          + " COUNT(*)::int AS replica_count"
          + " FROM pg_stat_replication WHERE state = 'streaming'";

  private final DataSource primary;
  private final AtomicReference<Double> latestLagSeconds = new AtomicReference<>(-1.0);
  private final AtomicReference<Integer> latestReplicaCount = new AtomicReference<>(-1);
  private final AtomicBoolean stopping = new AtomicBoolean(false);
  private ScheduledExecutorService scheduler;

  /** lag-aware quarantine 触发器(可选注入,不存在时只采集 metric 不触发 quarantine)。 */
  private ReadReplicaRoutingDataSource routingDataSource;

  /** lag-aware quarantine 阈值(秒);0 表示禁用。 */
  private int lagThresholdSeconds;

  @Value("${batch.console.replica.lag-monitor-interval-millis:30000}")
  private long intervalMillis;

  @Value("${batch.console.replica.lag-monitor-initial-delay-millis:10000}")
  private long initialDelayMillis;

  public ReplicaLagMonitor(
      DataSource primary, ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.primary = primary;
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry != null) {
      Gauge.builder(METRIC_NAME, latestLagSeconds, ref -> ref.get() == null ? -1.0 : ref.get())
          .description("PostgreSQL replication replay lag in seconds, -1 if unknown")
          .register(registry);
      Gauge.builder(
              METRIC_REPLICAS, latestReplicaCount, ref -> ref.get() == null ? -1.0 : ref.get())
          .description("PostgreSQL streaming replica count, -1 if unknown")
          .register(registry);
    }
  }

  /** lag-aware quarantine 注入。在 {@link ReadReplicaDataSourceConfiguration} 装配后调用。 */
  public void enableLagAwareQuarantine(
      ReadReplicaRoutingDataSource routingDataSource, int lagThresholdSeconds) {
    this.routingDataSource = routingDataSource;
    this.lagThresholdSeconds = lagThresholdSeconds;
  }

  @PostConstruct
  void start() {
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "replica-lag-monitor");
              t.setDaemon(true);
              return t;
            });
    scheduler.scheduleWithFixedDelay(
        this::sampleReplayLag, initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
    log.info(
        "replica lag monitor started: intervalMs={}, initialDelayMs={}",
        intervalMillis,
        initialDelayMillis);
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
      log.info("replica lag monitor stopping: source={}", source);
      scheduler.shutdownNow();
    }
  }

  void sampleReplayLag() {
    if (stopping.get()) {
      return;
    }
    try (Connection conn = primary.getConnection();
        PreparedStatement ps = conn.prepareStatement(LAG_QUERY);
        ResultSet rs = ps.executeQuery()) {
      double lag = 0.0;
      int replicas = 0;
      if (rs.next()) {
        lag = rs.getDouble(1);
        replicas = rs.getInt(2);
      }
      latestLagSeconds.set(lag);
      latestReplicaCount.set(replicas);

      // lag-aware quarantine 触发
      if (routingDataSource != null) {
        if (replicas == 0) {
          // 主库无任何 active replica → 读从库等于读断流(本地 dev env 见过 11 天)
          log.warn("no streaming replicas — forcing replica quarantine");
          routingDataSource.markQuarantined("no_streaming_replicas");
        } else if (lagThresholdSeconds > 0 && lag > lagThresholdSeconds) {
          log.warn(
              "replica replay lag {}s exceeds threshold {}s — quarantining",
              lag,
              lagThresholdSeconds);
          routingDataSource.markQuarantined("lag_exceeded");
        }
      }

      if (lag > 5.0) {
        log.warn("replica WAL replay lag is high: {}s (replicas={})", lag, replicas);
      } else {
        log.debug("replica WAL replay lag: {}s (replicas={})", lag, replicas);
      }
    } catch (SQLException ex) {
      if (stopping.get() && isShutdownNoise(ex)) {
        log.info("replica WAL replay lag sample skipped during shutdown: {}", ex.getMessage());
        return;
      }
      latestLagSeconds.set(-1.0);
      latestReplicaCount.set(-1);
      log.warn("failed to sample replica WAL replay lag: {}", ex.getMessage());
    } catch (RuntimeException ex) {
      if (stopping.get() && isShutdownNoise(ex)) {
        log.info("replica WAL replay lag sample skipped during shutdown: {}", ex.getMessage());
        return;
      }
      latestLagSeconds.set(-1.0);
      latestReplicaCount.set(-1);
      log.warn("unexpected error sampling replica lag: {}", ex.getMessage(), ex);
    }
  }

  /** 测试钩子：返回最近一次采集到的 lag 值。 */
  double currentLagSeconds() {
    Double v = latestLagSeconds.get();
    return v == null ? -1.0 : v;
  }

  private static boolean isShutdownNoise(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      String message = current.getMessage();
      if (message != null
          && (message.contains("has been closed")
              || message.contains("Connection pool shut down")
              || message.contains("Interrupted"))) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }
}
