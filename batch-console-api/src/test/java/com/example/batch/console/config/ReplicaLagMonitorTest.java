package com.example.batch.console.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.support.StaticApplicationContext;

/**
 * 单元测试：{@link ReplicaLagMonitor}.
 *
 * <p>覆盖：sampleReplayLag 成功 / SQLException 失败时 gauge 行为、gauge 注册、metric name 正确性。 不覆盖
 * ScheduledExecutorService 自身的调度循环（属 JDK 已验证组件）。
 */
class ReplicaLagMonitorTest {

  private DataSource primary;
  private Connection conn;
  private PreparedStatement ps;
  private ResultSet rs;
  private SimpleMeterRegistry meterRegistry;
  private ReplicaLagMonitor monitor;

  @BeforeEach
  void setUp() throws SQLException {
    primary = mock(DataSource.class);
    conn = mock(Connection.class);
    ps = mock(PreparedStatement.class);
    rs = mock(ResultSet.class);
    lenient().when(primary.getConnection()).thenReturn(conn);
    lenient().when(conn.prepareStatement(anyString())).thenReturn(ps);
    lenient().when(ps.executeQuery()).thenReturn(rs);

    meterRegistry = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
    lenient().when(provider.getIfAvailable()).thenReturn(meterRegistry);

    monitor = new ReplicaLagMonitor(primary, provider);
  }

  @Test
  void shouldRegisterGaugeWithExpectedName() {
    assertThat(meterRegistry.find("batch.console.replica.replay_lag_seconds").gauge()).isNotNull();
    assertThat(monitor.currentLagSeconds()).isEqualTo(-1.0); // 初始值
  }

  @Test
  void shouldUpdateGaugeOnSuccessfulSample() throws SQLException {
    when(rs.next()).thenReturn(true);
    when(rs.getDouble(1)).thenReturn(1.42);

    monitor.sampleReplayLag();

    assertThat(monitor.currentLagSeconds()).isEqualTo(1.42);
    assertThat(meterRegistry.find("batch.console.replica.replay_lag_seconds").gauge().value())
        .isEqualTo(1.42);
  }

  @Test
  void shouldHandleEmptyReplicationView() throws SQLException {
    // pg_stat_replication 没行（无 streaming replica）→ COALESCE 返回 0；ResultSet 仍有 1 行（聚合查询）
    when(rs.next()).thenReturn(true);
    when(rs.getDouble(1)).thenReturn(0.0);

    monitor.sampleReplayLag();

    assertThat(monitor.currentLagSeconds()).isEqualTo(0.0);
  }

  @Test
  void shouldFallBackToMinusOneOnSqlException() throws SQLException {
    when(primary.getConnection()).thenThrow(new SQLException("connection refused", "08001"));

    // 先模拟一次成功
    monitor.sampleReplayLag();
    // gauge 应仍为 -1（initial），因为 SQLException 导致重置为 -1
    assertThat(monitor.currentLagSeconds()).isEqualTo(-1.0);
  }

  @Test
  void shouldFallBackToMinusOneOnRuntimeException() throws SQLException {
    when(rs.next()).thenThrow(new RuntimeException("driver bug"));

    monitor.sampleReplayLag();

    assertThat(monitor.currentLagSeconds()).isEqualTo(-1.0);
  }

  @Test
  void shouldRecoverFromTransientFailureOnNextSample() throws SQLException {
    // 第一次失败
    when(primary.getConnection()).thenThrow(new SQLException("blip", "08001")).thenReturn(conn);
    monitor.sampleReplayLag();
    assertThat(monitor.currentLagSeconds()).isEqualTo(-1.0);

    // 第二次成功
    when(rs.next()).thenReturn(true);
    when(rs.getDouble(1)).thenReturn(0.3);
    monitor.sampleReplayLag();

    assertThat(monitor.currentLagSeconds()).isEqualTo(0.3);
  }

  @Test
  void shouldSkipSamplingAfterContextClosed() throws SQLException {
    monitor.stopOnContextClosed(new ContextClosedEvent(new StaticApplicationContext()));

    monitor.sampleReplayLag();

    org.mockito.Mockito.verify(primary, org.mockito.Mockito.never()).getConnection();
    assertThat(monitor.currentLagSeconds()).isEqualTo(-1.0);
  }
}
