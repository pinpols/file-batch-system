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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

/** {@link AtomicExecutorProductionGuard} 单测:验证 prod profile fail-closed 行为 + dev/local 放行行为。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("原子执行器生产守卫: 生产档位下的默认拒绝校验")
class AtomicExecutorProductionGuardTest {

  @Mock
  private ObjectProvider<SqlExecutorProperties> sqlProvider;

  @Mock
  private ObjectProvider<StoredProcExecutorProperties> spProvider;

  @Mock
  private ObjectProvider<HttpExecutorProperties> httpProvider;

  @Mock
  private ObjectProvider<ShellExecutorProperties> shellProvider;

  @Mock
  private ObjectProvider<SparkSubmitExecutorProperties> sparkProvider;

  private MockEnvironment env;

  @BeforeEach
  void setUp() {
    env = new MockEnvironment();
  }

  private AtomicExecutorProductionGuard newGuard() {
    AtomicExecutorGuardProperties guardProperties = Binder.get(env)
        .bind("batch.worker.executors.guard", Bindable.of(AtomicExecutorGuardProperties.class))
        .orElseGet(AtomicExecutorGuardProperties::new);
    return new AtomicExecutorProductionGuard(
        env, guardProperties, sqlProvider, spProvider, httpProvider, shellProvider, sparkProvider);
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
  @DisplayName("生产档位下数据库执行器已开启但未配置允许的数据源时, 启动校验应快速失败")
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
  @DisplayName("生产档位下存储过程执行器已开启但未限定允许的库模式时, 启动校验应快速失败")
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
  @DisplayName("生产档位下接口执行器既无主机白名单也未开启强制模式时, 启动校验应快速失败")
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
  @DisplayName("生产档位下接口执行器开启强制模式时, 即使白名单为空也应通过校验")
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
  @DisplayName("生产档位下命令执行器已开启但命令白名单为空时, 启动校验应快速失败")
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
  @DisplayName("生产档位下外部计算提交执行器已开启但应用资源白名单为空时, 启动校验应快速失败")
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
  @DisplayName("生产档位下各执行器都给出白名单与资源约束时, 启动校验应全部通过")
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
  @DisplayName("非生产档位下即使白名单全空也应放行, 保留开发友好语义")
  void shouldSkip_whenDevProfileEvenWithEmptyAllowlists() {
    // dev/local profile 即使全空也放行(保留开发友好语义)
    env.setActiveProfiles("dev");
    // 完全不必 stub provider —— guard 早在 profile 检查处 return,不会查 provider
    newGuard().verifyProductionFailClosed(); // 不抛
  }

  @Test
  @DisplayName("未声明激活档位时应直接放行, 不进入生产校验分支")
  void shouldSkip_whenNoActiveProfile() {
    // 未声明 active profile 也放行
    newGuard().verifyProductionFailClosed(); // 不抛
  }

  @Test
  @DisplayName("复合档位名以生产前缀开头时应按生产处理, 触发同样的校验")
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
  @DisplayName("生产档位下存在多处配置缺口时应一次性收集全部违规项, 便于一次修完")
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
  @DisplayName("档位被显式列入强制执行清单时, 即使名称不含生产前缀也必须校验")
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
  @DisplayName("开关置为始终强制执行时, 开发档位同样要走生产校验")
  void shouldEnforce_whenAlwaysEnforceTrueEvenOnDev() {
    env.setActiveProfiles("dev");
    env.setProperty("batch.worker.executors.guard.always-enforce", "true");
    stubAllWith(enabledSqlEmptyAllowlist(), disabledSp(), disabledHttp(), disabledShell());

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allowed-data-source-beans");
  }

  @Test
  @DisplayName("预发档位存在配置缺口时应与生产一致地快速失败, 不依赖额外名单配置")
  void shouldFailFast_whenStagingProfileHasViolations() {
    // staging 与生产同样按 prod-like 处理，不能因漏配 enforce-profiles 而放行。
    env.setActiveProfiles("staging");
    stubAllWith(enabledSqlEmptyAllowlist(), disabledSp(), disabledHttp(), disabledShell());

    assertThatThrownBy(() -> newGuard().verifyProductionFailClosed())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("allowed-data-source-beans");
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
