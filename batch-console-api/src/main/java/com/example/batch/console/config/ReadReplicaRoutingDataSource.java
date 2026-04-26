package com.example.batch.console.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 读写分离路由 DataSource，在 {@link AbstractRoutingDataSource} 之上加两层增强：
 *
 * <ol>
 *   <li><b>force-primary 旁路</b>：{@link RoutingHints#isForcePrimary()} 为 true 时直接返回 PRIMARY， 优先级高于
 *       readOnly 标志（read-after-write 场景）
 *   <li><b>fail-open 降级</b>：从库连接失败累计达 {@code failureThreshold} 次后进入 quarantine， quarantine 期内
 *       readOnly 查询自动落主库（避免从库故障击穿业务）；quarantine 期满后下一次请求重新尝试， 成功即解除，失败则续期。Hikari 自身的连接失败也会被
 *       try-catch 捕获并降级。
 * </ol>
 *
 * <p>暴露的指标（micrometer）：
 *
 * <ul>
 *   <li>{@code batch.console.replica.failover.count} — 单调计数器，每次降级 +1
 *   <li>{@code batch.console.replica.connection.failure} — 单调计数器，每次从库 SQLException +1
 * </ul>
 */
@Slf4j
public class ReadReplicaRoutingDataSource extends AbstractRoutingDataSource {

  enum Route {
    PRIMARY,
    REPLICA
  }

  private static final String METRIC_FAILOVER = "batch.console.replica.failover.count";
  private static final String METRIC_FAILURE = "batch.console.replica.connection.failure";

  private final DataSource primary;
  private final int failureThreshold;
  private final long quarantineMillis;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;

  // C-3.1：连续失败计数 + quarantine 截止时间。volatile 足够：单调写、读容忍滞后一次请求。
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private volatile long quarantineUntilMillis = 0L;

  public ReadReplicaRoutingDataSource(
      DataSource primary,
      int failureThreshold,
      long quarantineMillis,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.primary = primary;
    this.failureThreshold = Math.max(1, failureThreshold);
    this.quarantineMillis = Math.max(1_000L, quarantineMillis);
    this.meterRegistryProvider = meterRegistryProvider;
  }

  @Override
  protected Object determineCurrentLookupKey() {
    if (RoutingHints.isForcePrimary()) {
      return Route.PRIMARY;
    }
    if (System.currentTimeMillis() < quarantineUntilMillis) {
      // quarantine 期内静默走主库；失败计数仍保留以便观察恢复时是否再次失败
      return Route.PRIMARY;
    }
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      return Route.REPLICA;
    }
    return Route.PRIMARY;
  }

  @Override
  public Connection getConnection() throws SQLException {
    Object key = determineCurrentLookupKey();
    if (key == Route.PRIMARY) {
      return primary.getConnection();
    }
    try {
      Connection conn = super.getConnection();
      // 成功一次就重置连续失败计数
      if (consecutiveFailures.get() > 0) {
        consecutiveFailures.set(0);
      }
      return conn;
    } catch (SQLException ex) {
      handleReplicaFailure(ex);
      log.warn("replica connection failed; failing open to primary: cause={}", rootMessage(ex));
      return primary.getConnection();
    }
  }

  private void handleReplicaFailure(SQLException ex) {
    incrementCounter(METRIC_FAILURE, "type", classify(ex));
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= failureThreshold) {
      quarantineUntilMillis = System.currentTimeMillis() + quarantineMillis;
      log.warn(
          "replica entered quarantine for {}ms after {} consecutive failures",
          quarantineMillis,
          failures);
    }
    incrementCounter(METRIC_FAILOVER, "reason", "connection_failure");
  }

  private void incrementCounter(String name, String tagKey, String tagValue) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (registry == null) {
      return;
    }
    Counter.builder(name).tags(Tags.of(tagKey, tagValue)).register(registry).increment();
  }

  private static String classify(SQLException ex) {
    String state = ex.getSQLState();
    if (state == null) {
      return "unknown";
    }
    if (state.startsWith("08")) {
      return "connection";
    }
    if (state.startsWith("57")) {
      return "operator_intervention";
    }
    return state;
  }

  private static String rootMessage(SQLException ex) {
    Throwable t = ex;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t.getMessage();
  }

  /** 当前是否处于 quarantine 状态（用于测试/监控查询）。 */
  public boolean isReplicaQuarantined() {
    return System.currentTimeMillis() < quarantineUntilMillis;
  }

  /** 当前连续失败计数（用于测试）。 */
  public int currentConsecutiveFailures() {
    return consecutiveFailures.get();
  }
}
