package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.atomic.config.AtomicWorkerConfiguration;
import io.github.pinpols.batch.worker.atomic.http.HttpExecutorProperties;
import io.github.pinpols.batch.worker.atomic.shell.ShellExecutorProperties;
import io.github.pinpols.batch.worker.atomic.sql.SqlExecutorProperties;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/**
 * Round-3 #8:{@link AtomicRuntimeStatusService} 单测 — 4 个 executor 快照字段 + enforce-allowlist 来源分类
 * (explicit / prod-default / dev-default)+ SQL dialect 容错。
 */
@ExtendWith(MockitoExtension.class)
class AtomicRuntimeStatusServiceTest {

  @Mock private ObjectProvider<ShellExecutorProperties> shellProvider;
  @Mock private ObjectProvider<SqlExecutorProperties> sqlProvider;
  @Mock private ObjectProvider<HttpExecutorProperties> httpProvider;
  @Mock private ObjectProvider<StoredProcExecutorProperties> storedProcProvider;
  @Mock private ObjectProvider<DataSource> dataSourceProvider;

  private MockEnvironment env;
  private AtomicWorkerConfiguration workerCfg;

  @BeforeEach
  void setUp() {
    env = new MockEnvironment();
    workerCfg =
        new AtomicWorkerConfiguration(
            "atomic-node-1", "ATOMIC", "tenant-x", 15000L, "topic.x", "group.x", List.of());
  }

  private AtomicRuntimeStatusService newService() {
    return new AtomicRuntimeStatusService(
        workerCfg,
        env,
        shellProvider,
        sqlProvider,
        httpProvider,
        storedProcProvider,
        dataSourceProvider);
  }

  @Test
  void shouldCaptureAllFourExecutorStatuses_underNormalProdConfiguration() throws Exception {
    // 准备:prod profile,http 未显式配置 → enforceAllowlistSource = prod-default
    env.setActiveProfiles("prod");

    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(true);
    shell.setCommandWhitelist(Set.of("/usr/bin/curl", "/usr/bin/wget"));
    when(shellProvider.getIfAvailable()).thenReturn(shell);

    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(true);
    when(sqlProvider.getIfAvailable()).thenReturn(sql);

    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(true);
    http.setEnforceAllowlist(true); // 已被 HttpExecutorProdDefaults 翻过
    http.setAllowedHostPatterns(Set.of("api.internal.*", "*.example.com"));
    when(httpProvider.getIfAvailable()).thenReturn(http);

    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(true);
    sp.setAllowedSchemas(Set.of("batch", "app"));
    when(storedProcProvider.getIfAvailable()).thenReturn(sp);

    DataSource ds = org.mockito.Mockito.mock(DataSource.class);
    Connection conn = org.mockito.Mockito.mock(Connection.class);
    DatabaseMetaData md = org.mockito.Mockito.mock(DatabaseMetaData.class);
    when(ds.getConnection()).thenReturn(conn);
    when(conn.getMetaData()).thenReturn(md);
    when(md.getDatabaseProductName()).thenReturn("PostgreSQL");
    when(dataSourceProvider.getIfAvailable()).thenReturn(ds);

    // 执行
    AtomicRuntimeStatus status = newService().snapshot();

    // 断言
    assertThat(status.workerCode()).isEqualTo("atomic-node-1");
    assertThat(status.shell().enabled()).isTrue();
    assertThat(status.shell().commandWhitelistSize()).isEqualTo(2);
    assertThat(status.sql().enabled()).isTrue();
    assertThat(status.sql().dialect()).isEqualTo("PostgreSQL");
    assertThat(status.http().enabled()).isTrue();
    assertThat(status.http().enforceAllowlist()).isTrue();
    assertThat(status.http().enforceAllowlistSource()).isEqualTo("prod-default");
    assertThat(status.http().allowlistHostsSize()).isEqualTo(2);
    assertThat(status.storedProc().enabled()).isTrue();
    assertThat(status.storedProc().allowedSchemasSize()).isEqualTo(2);
  }

  @Test
  void shouldReportExplicitSource_whenHttpEnforceAllowlistConfiguredInEnvironment() {
    env.setActiveProfiles("prod");
    env.setProperty(HttpExecutorProdDefaults.PROP_ENFORCE_ALLOWLIST, "false");
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnforceAllowlist(false);
    when(httpProvider.getIfAvailable()).thenReturn(http);

    assertThat(newService().snapshot().http().enforceAllowlistSource()).isEqualTo("explicit");
  }

  @Test
  void shouldReportDevDefaultSource_whenNotProdProfile() {
    env.setActiveProfiles("dev");
    HttpExecutorProperties http = new HttpExecutorProperties();
    when(httpProvider.getIfAvailable()).thenReturn(http);

    assertThat(newService().snapshot().http().enforceAllowlistSource()).isEqualTo("dev-default");
  }

  @Test
  void shouldFallbackUnknownDialect_whenDataSourceConnectionFails() throws Exception {
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(true);
    when(sqlProvider.getIfAvailable()).thenReturn(sql);
    DataSource ds = org.mockito.Mockito.mock(DataSource.class);
    when(ds.getConnection()).thenThrow(new java.sql.SQLException("boom"));
    when(dataSourceProvider.getIfAvailable()).thenReturn(ds);

    assertThat(newService().snapshot().sql().dialect()).isEqualTo("unknown");
  }

  @Test
  void shouldReturnDisabledStatus_whenPropertiesBeansAbsent() {
    when(shellProvider.getIfAvailable()).thenReturn(null);
    when(sqlProvider.getIfAvailable()).thenReturn(null);
    when(httpProvider.getIfAvailable()).thenReturn(null);
    when(storedProcProvider.getIfAvailable()).thenReturn(null);

    AtomicRuntimeStatus status = newService().snapshot();
    assertThat(status.shell().enabled()).isFalse();
    assertThat(status.sql().enabled()).isFalse();
    assertThat(status.sql().dialect()).isEqualTo("n/a");
    assertThat(status.http().enabled()).isFalse();
    assertThat(status.http().enforceAllowlistSource()).isEqualTo("absent");
    assertThat(status.storedProc().enabled()).isFalse();
  }
}
