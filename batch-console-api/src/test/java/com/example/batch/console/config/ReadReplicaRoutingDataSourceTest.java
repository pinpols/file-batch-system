package com.example.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.config.ReadReplicaRoutingDataSource.Route;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockingDetails;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * ReadReplicaRoutingDataSource 单元测试：覆盖路由决策、force-primary 旁路、fail-open 降级、quarantine 闭环。
 */
class ReadReplicaRoutingDataSourceTest {

  private DataSource primary;
  private DataSource replica;
  private Connection primaryConn;
  private Connection replicaConn;
  private SimpleMeterRegistry meterRegistry;
  private ObjectProvider<MeterRegistry> meterProvider;

  @BeforeEach
  void setUp() throws SQLException {
    primary = mock(DataSource.class);
    replica = mock(DataSource.class);
    primaryConn = mock(Connection.class);
    replicaConn = mock(Connection.class);
    lenient().when(primary.getConnection()).thenReturn(primaryConn);
    lenient().when(replica.getConnection()).thenReturn(replicaConn);
    meterRegistry = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> mockedProvider = mock(ObjectProvider.class);
    meterProvider = mockedProvider;
    lenient().when(meterProvider.getIfAvailable()).thenReturn(meterRegistry);
  }

  @AfterEach
  void tearDown() {
    TransactionSynchronizationManager.clear();
    // RoutingHints ThreadLocal cleanup
    RoutingHints.restore(null);
  }

  private ReadReplicaRoutingDataSource buildDs(int threshold, long quarantineMs) {
    ReadReplicaRoutingDataSource ds =
        new ReadReplicaRoutingDataSource(primary, threshold, quarantineMs, meterProvider);
    ds.setTargetDataSources(Map.of(Route.PRIMARY, primary, Route.REPLICA, replica));
    ds.setDefaultTargetDataSource(primary);
    ds.afterPropertiesSet();
    return ds;
  }

  // ── 路由决策 ───────────────────────────────────────────────────────

  @Test
  void readOnlyTransactionRoutesToReplica() throws SQLException {
    ReadReplicaRoutingDataSource ds = buildDs(3, 30_000);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    Connection actual = ds.getConnection();

    assertThat(actual).isSameAs(replicaConn);
    verify(replica).getConnection();
    verify(primary, never()).getConnection();
  }

  @Test
  void writeTransactionRoutesToPrimary() throws SQLException {
    ReadReplicaRoutingDataSource ds = buildDs(3, 30_000);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    Connection actual = ds.getConnection();

    assertThat(actual).isSameAs(primaryConn);
    verify(primary).getConnection();
    verify(replica, never()).getConnection();
  }

  @Test
  void noTransactionRoutesToPrimary() throws SQLException {
    ReadReplicaRoutingDataSource ds = buildDs(3, 30_000);
    // no transaction synchronization

    Connection actual = ds.getConnection();

    assertThat(actual).isSameAs(primaryConn);
  }

  // ── force-primary 旁路 ─────────────────────────────────────────────

  @Test
  void forcePrimaryHintOverridesReadOnly() throws SQLException {
    ReadReplicaRoutingDataSource ds = buildDs(3, 30_000);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    Boolean prev = RoutingHints.enterForcePrimary();
    try {
      Connection actual = ds.getConnection();
      assertThat(actual).isSameAs(primaryConn);
    } finally {
      RoutingHints.restore(prev);
    }
    verify(replica, never()).getConnection();
  }

  // ── fail-open 单次降级 ─────────────────────────────────────────────

  @Test
  void replicaSqlExceptionFailsOverToPrimary() throws SQLException {
    ReadReplicaRoutingDataSource ds = buildDs(3, 30_000);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    when(replica.getConnection()).thenThrow(new SQLException("connect refused", "08001"));

    Connection actual = ds.getConnection();

    assertThat(actual).isSameAs(primaryConn);
    verify(replica).getConnection();
    verify(primary).getConnection();
    assertThat(meterRegistry.find("batch.console.replica.connection.failure").counter()).isNotNull();
    assertThat(ds.currentConsecutiveFailures()).isEqualTo(1);
    assertThat(ds.isReplicaQuarantined()).isFalse();
  }

  // ── quarantine 进入 ─────────────────────────────────────────────────

  @Test
  void replicaEntersQuarantineAfterThresholdFailures() throws SQLException {
    ReadReplicaRoutingDataSource ds = buildDs(3, 30_000);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    when(replica.getConnection()).thenThrow(new SQLException("down", "08006"));

    // 触发 3 次失败
    for (int i = 0; i < 3; i++) {
      ds.getConnection();
    }

    assertThat(ds.currentConsecutiveFailures()).isEqualTo(3);
    assertThat(ds.isReplicaQuarantined()).isTrue();
  }

  @Test
  void quarantineRoutesAllReadsToPrimaryWithoutTryingReplica() throws SQLException {
    ReadReplicaRoutingDataSource ds = buildDs(2, 30_000);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    when(replica.getConnection()).thenThrow(new SQLException("down", "08006"));

    // 进入 quarantine
    ds.getConnection();
    ds.getConnection();
    assertThat(ds.isReplicaQuarantined()).isTrue();

    // quarantine 期内的请求不应再调 replica.getConnection
    int callsBefore = mockingDetails(replica).getInvocations().size();
    Connection actual = ds.getConnection();
    int callsAfter = mockingDetails(replica).getInvocations().size();

    assertThat(actual).isSameAs(primaryConn);
    assertThat(callsAfter).isEqualTo(callsBefore); // 没新调用
  }

  // ── 成功后失败计数重置 ──────────────────────────────────────────────

  @Test
  void successfulReplicaConnectionResetsFailureCount() throws SQLException {
    ReadReplicaRoutingDataSource ds = buildDs(3, 30_000);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

    // 1 次失败
    when(replica.getConnection())
        .thenThrow(new SQLException("transient", "08001"))
        .thenReturn(replicaConn);
    ds.getConnection();
    assertThat(ds.currentConsecutiveFailures()).isEqualTo(1);

    // 第 2 次成功 → 重置
    Connection actual = ds.getConnection();
    assertThat(actual).isSameAs(replicaConn);
    assertThat(ds.currentConsecutiveFailures()).isZero();
  }

  // ── quarantine 期满自动恢复 ─────────────────────────────────────────

  @Test
  void quarantineExpiresAfterTimeout() throws Exception {
    // 注意：构造器对 quarantineMillis 有 Math.max(1_000L, ...) 下限，本测试用 1100ms 才能跨过这个 floor
    ReadReplicaRoutingDataSource ds = buildDs(1, 1100);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
    when(replica.getConnection())
        .thenThrow(new SQLException("flap", "08006"))
        .thenReturn(replicaConn);

    // 进入 quarantine
    ds.getConnection();
    assertThat(ds.isReplicaQuarantined()).isTrue();

    // 等 quarantine 过期
    Thread.sleep(1300);
    assertThat(ds.isReplicaQuarantined()).isFalse();

    // 下一次请求重新尝试 replica，成功
    Connection actual = ds.getConnection();
    assertThat(actual).isSameAs(replicaConn);
  }

  private static MockingDetails mockingDetails(Object target) {
    return Mockito.mockingDetails(target);
  }
}
