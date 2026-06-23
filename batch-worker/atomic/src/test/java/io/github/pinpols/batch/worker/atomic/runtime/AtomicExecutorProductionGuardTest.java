package io.github.pinpols.batch.worker.atomic.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.worker.atomic.http.HttpExecutorProperties;
import io.github.pinpols.batch.worker.atomic.shell.ShellExecutorProperties;
import io.github.pinpols.batch.worker.atomic.spark.SparkSubmitExecutorProperties;
import io.github.pinpols.batch.worker.atomic.sql.SqlExecutorProperties;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
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
  @Mock private ObjectProvider<SparkSubmitExecutorProperties> sparkProvider;

  private MockEnvironment env;

  @BeforeEach
  void setUp() {
    env = new MockEnvironment();
  }

  private AtomicExecutorProductionGuard newGuard() {
    return new AtomicExecutorProductionGuard(
        env, sqlProvider, spProvider, httpProvider, shellProvider, sparkProvider);
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
    // spark 默认禁用(不参与既有用例);spark 专项用例自行 stub 启用态。
    when(sparkProvider.getIfAvailable()).thenReturn(new SparkSubmitExecutorProperties());
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
  void shouldFailFast_whenProdProfileAndSparkAppResourceAllowlistEmpty() {
    env.setActiveProfiles("prod");
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(false);
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(false);
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(false);
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(false);
    SparkSubmitExecutorProperties spark = new SparkSubmitExecutorProperties();
    spark.setEnabled(true); // appResourceAllowlist 默认空 → prod 下任意 jar 提交 = RCE
    when(sqlProvider.getIfAvailable()).thenReturn(sql);
    when(spProvider.getIfAvailable()).thenReturn(sp);
    when(httpProvider.getIfAvailable()).thenReturn(http);
    when(shellProvider.getIfAvailable()).thenReturn(shell);
    when(sparkProvider.getIfAvailable()).thenReturn(spark);

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.worker.executors.spark-submit")
        .hasMessageContaining("app-resource-allowlist");
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

  @Test
  void shouldEnforce_whenProfileListedInEnforceProfiles() {
    // staging 不含 "prod",但被配置进 enforce-profiles → 同样 fail-closed
    env.setActiveProfiles("staging");
    env.setProperty("batch.worker.executors.guard.enforce-profiles", "staging,uat");
    stubAllWith(enabledSqlEmptyAllowlist(), disabledSp(), disabledHttp(), disabledShell());

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allowed-data-source-beans");
  }

  @Test
  void shouldEnforce_whenAlwaysEnforceTrueEvenOnDev() {
    env.setActiveProfiles("dev");
    env.setProperty("batch.worker.executors.guard.always-enforce", "true");
    stubAllWith(enabledSqlEmptyAllowlist(), disabledSp(), disabledHttp(), disabledShell());

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allowed-data-source-beans");
  }

  @Test
  void shouldOnlyWarn_whenNonEnforcedProfileHasViolations() {
    // staging 未列入 enforce-profiles:检出违规也不抛(降级 WARN),保持非强制环境可启动
    env.setActiveProfiles("staging");
    stubAllWith(enabledSqlEmptyAllowlist(), disabledSp(), disabledHttp(), disabledShell());

    newGuard().verifyProductionFailClosed(); // 不抛,仅 WARN
  }

  private static SqlExecutorProperties enabledSqlEmptyAllowlist() {
    SqlExecutorProperties sql = new SqlExecutorProperties();
    sql.setEnabled(true); // allowedDataSourceBeans 默认空 = 违规
    return sql;
  }

  private static StoredProcExecutorProperties disabledSp() {
    StoredProcExecutorProperties sp = new StoredProcExecutorProperties();
    sp.setEnabled(false);
    return sp;
  }

  private static HttpExecutorProperties disabledHttp() {
    HttpExecutorProperties http = new HttpExecutorProperties();
    http.setEnabled(false);
    return http;
  }

  private static ShellExecutorProperties disabledShell() {
    ShellExecutorProperties shell = new ShellExecutorProperties();
    shell.setEnabled(false);
    return shell;
  }
}
