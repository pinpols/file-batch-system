package com.example.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.example.batch.worker.atomic.http.HttpExecutorProperties;
import com.example.batch.worker.atomic.shell.ShellExecutorProperties;
import com.example.batch.worker.atomic.sql.SqlExecutorProperties;
import com.example.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

/** {@link AtomicExecutorProductionGuard} 单测:验证 prod profile fail-closed 行为 + dev/local 放行行为。 */
@ExtendWith(MockitoExtension.class)
class AtomicExecutorProductionGuardTest {

  @Mock private ObjectProvider<SqlExecutorProperties> sqlProvider;
  @Mock private ObjectProvider<StoredProcExecutorProperties> spProvider;
  @Mock private ObjectProvider<HttpExecutorProperties> httpProvider;
  @Mock private ObjectProvider<ShellExecutorProperties> shellProvider;

  private MockEnvironment env;

  @BeforeEach
  void setUp() {
    env = new MockEnvironment();
  }

  private AtomicExecutorProductionGuard newGuard() {
    return new AtomicExecutorProductionGuard(
        env, sqlProvider, spProvider, httpProvider, shellProvider);
  }

  private void stubAllWith(
      SqlExecutorProperties sql,
      StoredProcExecutorProperties sp,
      HttpExecutorProperties http,
      ShellExecutorProperties shell) {
    when(sqlProvider.getIfAvailable()).thenReturn(sql);
    when(spProvider.getIfAvailable()).thenReturn(sp);
    when(httpProvider.getIfAvailable()).thenReturn(http);
    when(shellProvider.getIfAvailable()).thenReturn(shell);
  }

  @Test
  void shouldFailFast_whenProdProfileAndSqlAllowedDataSourceBeansEmpty() {
    env.setActiveProfiles("prod");
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(true); // allowedDataSourceBeans 默认空
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(false);
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(false);
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(false);
    stubAllWith(sql, sp, http, shell);

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.worker.executors.sql")
        .hasMessageContaining("allowed-data-source-beans");
  }

  @Test
  void shouldFailFast_whenProdProfileAndStoredProcAllowedSchemasEmpty() {
    env.setActiveProfiles("prod");
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(false);
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(true);
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(false);
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(false);
    stubAllWith(sql, sp, http, shell);

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.worker.executors.stored-proc")
        .hasMessageContaining("allowed-schemas");
  }

  @Test
  void shouldFailFast_whenProdProfileAndHttpAllowlistEmptyAndNotEnforced() {
    env.setActiveProfiles("prod");
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(false);
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(false);
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(true); // allowedHostPatterns 默认空,enforceAllowlist 默认 false
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(false);
    stubAllWith(sql, sp, http, shell);

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.worker.executors.http")
        .hasMessageContaining("enforce-allowlist");
  }

  @Test
  void shouldPass_whenProdProfileAndHttpEnforceAllowlistTrue() {
    env.setActiveProfiles("prod");
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(false);
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(false);
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(true);
    http.setEnforceAllowlist(true); // 空白名单 + 强制 = fail-closed,合规
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(false);
    stubAllWith(sql, sp, http, shell);

    newGuard().verifyProductionFailClosed(); // 不抛
  }

  @Test
  void shouldFailFast_whenProdProfileAndShellWhitelistEmpty() {
    env.setActiveProfiles("prod");
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(false);
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(false);
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(false);
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(true); // commandWhitelist 默认空
    stubAllWith(sql, sp, http, shell);

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.worker.executors.shell")
        .hasMessageContaining("command-whitelist");
  }

  @Test
  void shouldPass_whenProdProfileAndAllExecutorsConfigured() {
    env.setActiveProfiles("prod");
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(true);
    sql.setAllowedDataSourceBeans(Set.of("batchReportDs"));
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(true);
    sp.setAllowedSchemas(Set.of("batch"));
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(true);
    http.setAllowedHostPatterns(Set.of("*.internal.example.com"));
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(true);
    shell.setCommandWhitelist(Set.of("/usr/bin/python3"));
    stubAllWith(sql, sp, http, shell);

    newGuard().verifyProductionFailClosed(); // 不抛
  }

  @Test
  void shouldSkip_whenDevProfileEvenWithEmptyAllowlists() {
    // dev/local profile 即使全空也放行(保留开发友好语义)
    env.setActiveProfiles("dev");
    // 完全不必 stub provider —— guard 早在 profile 检查处 return,不会查 provider
    newGuard().verifyProductionFailClosed(); // 不抛
  }

  @Test
  void shouldSkip_whenNoActiveProfile() {
    // 未声明 active profile 也放行
    newGuard().verifyProductionFailClosed(); // 不抛
  }

  @Test
  void shouldDetectProd_whenCompositeProfileLikeProdEu() {
    env.setActiveProfiles("prod-eu");
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(true);
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(false);
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(false);
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(false);
    stubAllWith(sql, sp, http, shell);

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allowed-data-source-beans");
  }

  @Test
  void shouldAggregateMultipleViolations_inProd() {
    env.setActiveProfiles("prod");
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(true);
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(true);
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(false);
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(false);
    stubAllWith(sql, sp, http, shell);

    AtomicExecutorProductionGuard guard = newGuard();
    assertThat(guard.collectViolations()).hasSize(2);
  }
}
