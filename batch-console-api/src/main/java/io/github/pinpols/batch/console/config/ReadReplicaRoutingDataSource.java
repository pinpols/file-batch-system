package io.github.pinpols.batch.console.config;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
 *   <li><b>fail-open 降级</b>：从库连接获取或执行阶段的连接性失败累计达 {@code failureThreshold} 次后进入
 *       quarantine，期内 readOnly 查询自动落主库（避免从库故障击穿业务）；quarantine 期满后下一次请求重新尝试，
 *       成功即解除，失败则续期。已绑定从库连接的当前事务不会中途换库；该请求可能失败，但后续请求会走主库。
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
  private static final String METRIC_RECOVERY = "batch.console.replica.recovery.count";

  private final DataSource primary;
  private final int failureThreshold;
  private final long quarantineMillis;
  private final ObjectProvider<MeterRegistry> meterRegistryProvider;

  // C-3.1：连续失败计数 + quarantine 截止时间。volatile 足够：单调写、读容忍滞后一次请求。
  private final AtomicInteger consecutiveFailures = new AtomicInteger();
  private volatile long quarantineUntilMillis = 0L;

  // v6 hardening：标记"曾进入过 quarantine"，让首次恢复成功时能发 info 日志 / metric，
  // 避免 quarantine 期满后静默恢复（运维看不到恢复信号）。
  private volatile boolean quarantineEverEntered = false;

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
    if (BatchDateTimeSupport.utcEpochMillis() < quarantineUntilMillis) {
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
      Connection conn = observeReplicaConnection(super.getConnection());
      // 成功一次就重置连续失败计数；若曾进入过 quarantine，此次成功即视为"replica 恢复"，
      // 发 info 日志 + recovery counter 让运维明确感知到恢复信号（quarantineUntilMillis
      // 是隐式时间过期，无显式 transition 事件，否则故障 → 恢复对运维静默）。
      int prior = consecutiveFailures.getAndSet(0);
      if (quarantineEverEntered) {
        quarantineEverEntered = false;
        quarantineUntilMillis = 0L;
        log.info("replica recovered after quarantine: priorConsecutiveFailures={}", prior);
        incrementCounter(METRIC_RECOVERY, "reason", "connection_restored");
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
      quarantineUntilMillis = BatchDateTimeSupport.utcEpochMillis() + quarantineMillis;
      quarantineEverEntered = true;
      log.warn(
          "replica entered quarantine for {}ms after {} consecutive failures",
          quarantineMillis,
          failures);
    }
    incrementCounter(METRIC_FAILOVER, "reason", "connection_failure");
  }

  /**
   * 连接成功后，继续观察 JDBC 执行阶段的连接性异常。
   *
   * <p>只在取连接时做 fail-open 会漏掉“连接建立成功、执行查询时断链”的故障。当前事务无法在这里安全切换连接，
   * 但必须立即隔离副本，让后续请求走主库；调用方仍会收到当前查询的原始 SQLException。
   */
  private Connection observeReplicaConnection(Connection connection) {
    return proxyJdbcObject(connection, Connection.class);
  }

  @SuppressWarnings("unchecked")
  private <T> T proxyJdbcObject(T target, Class<?> requiredInterface) {
    if (EmptyChecks.isNull(target) || !requiredInterface.isInterface()) {
      return target;
    }
    LinkedHashSet<Class<?>> interfaceSet = new LinkedHashSet<>();
    interfaceSet.add(requiredInterface);
    if (target instanceof PreparedStatement) {
      interfaceSet.add(PreparedStatement.class);
    }
    if (target instanceof CallableStatement) {
      interfaceSet.add(CallableStatement.class);
    }
    if (target instanceof ResultSet) {
      interfaceSet.add(ResultSet.class);
    }
    if (target instanceof Statement) {
      interfaceSet.add(Statement.class);
    }
    List<Class<?>> interfaces = new ArrayList<>(interfaceSet);
    InvocationHandler handler =
        (proxy, method, args) -> invokeObserved(target, proxy, method, args);
    return (T) Proxy.newProxyInstance(
        target.getClass().getClassLoader(), interfaces.toArray(Class<?>[]::new), handler);
  }

  private Object invokeObserved(Object target, Object proxy, Method method, Object[] args)
      throws Throwable {
    try {
      Object result = method.invoke(target, args);
      if (result instanceof Statement statement) {
        return proxyJdbcObject(statement, Statement.class);
      }
      if (result instanceof ResultSet resultSet) {
        return proxyJdbcObject(resultSet, ResultSet.class);
      }
      if (result instanceof Connection returnedConnection) {
        return proxyJdbcObject(returnedConnection, Connection.class);
      }
      return result;
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof SQLException sqlException && isConnectionFailure(sqlException)) {
        handleReplicaFailure(sqlException);
      }
      throw cause;
    }
  }

  private static boolean isConnectionFailure(SQLException exception) {
    SQLException current = exception;
    while (EmptyChecks.isNotNull(current)) {
      String state = current.getSQLState();
      if (EmptyChecks.isNotNull(state) && (state.startsWith("08") || state.startsWith("57"))) {
        return true;
      }
      Throwable cause = current.getCause();
      current = cause instanceof SQLException sqlException ? sqlException : null;
    }
    return false;
  }

  private void incrementCounter(String name, String tagKey, String tagValue) {
    MeterRegistry registry = meterRegistryProvider.getIfAvailable();
    if (EmptyChecks.isNull(registry)) {
      return;
    }
    Counter.builder(name).tags(Tags.of(tagKey, tagValue)).register(registry).increment();
  }

  private static String classify(SQLException ex) {
    String state = ex.getSQLState();
    if (EmptyChecks.isNull(state)) {
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
    return BatchDateTimeSupport.utcEpochMillis() < quarantineUntilMillis;
  }

  /**
   * 外部触发 quarantine(连接层之外的故障,如 WAL replay lag 过大、replica 全断)。
   *
   * <p>由 {@link ReplicaLagMonitor} 在采样到 lag > 阈值 或 streaming replica 数=0 时调用。 老的 fail-open 只看
   * SQLException,识别不出「连得上但 stale」的情况。
   *
   * @param reason 触发原因 tag(如 lag_exceeded / no_streaming_replicas),进 Prometheus counter
   */
  public void markQuarantined(String reason) {
    long until = BatchDateTimeSupport.utcEpochMillis() + quarantineMillis;
    // 只在比当前更晚时更新,避免短暂故障被 lag-aware 频繁延长
    if (until > quarantineUntilMillis) {
      quarantineUntilMillis = until;
      quarantineEverEntered = true;
      log.warn("replica externally quarantined for {}ms (reason={})", quarantineMillis, reason);
      incrementCounter(METRIC_FAILOVER, "reason", reason);
    }
  }

  /** 当前连续失败计数（用于测试）。 */
  public int currentConsecutiveFailures() {
    return consecutiveFailures.get();
  }
}
