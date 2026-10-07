package io.github.pinpols.batch.worker.atomic.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.spi.task.TaskContext;
import io.github.pinpols.batch.common.spi.task.TaskResult;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import io.github.pinpols.batch.testing.OrchestratorWireMockSupport;
import io.github.pinpols.batch.worker.atomic.BatchWorkerAtomicApplication;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcExecutorProperties;
import io.github.pinpols.batch.worker.atomic.storedproc.StoredProcTaskExecutor;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * StoredProc hardening 的真 testcontainers PG 集成测试 —— 验证只能打真库才能验的行为(单测是 mock JDBC):
 *
 * <ul>
 *   <li>prokind 路由:真 PROCEDURE 走原生 {@code CALL},真 FUNCTION 走 {@code {call}} 转义
 *   <li>{@code SET LOCAL search_path} pin:非 qualified 名靠 defaultSchema pin 才能解析
 *   <li>REFCURSOR 在 {@code maxRefCursorRows} 下截断
 *   <li>{@code verifyExecutePrivilege}:current_user 有 EXECUTE 时 has_function_privilege 放行
 * </ul>
 *
 * <p>低权限拒绝路径(current_user 无 EXECUTE)在 testcontainers 超级用户下无法构造(superuser 绕过
 * has_function_privilege),需独立低权限 datasource,留作后续;此处覆盖 happy path + 上述真库行为。
 */
@SpringBootTest(
    classes = BatchWorkerAtomicApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("存储过程加固集成: 真实数据库下的调用路由与权限校验")
class StoredProcHardeningIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired
  DataSource dataSource;

  @Autowired
  BeanFactory beanFactory;

  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP SCHEMA IF EXISTS spi_it CASCADE");
    jdbc.execute("CREATE SCHEMA spi_it");
    jdbc.execute("CREATE TABLE spi_it.marker(id serial primary key, note text)");
    // 真 PROCEDURE(prokind 'p' → 原生 CALL)
    jdbc.execute("CREATE PROCEDURE spi_it.it_proc() LANGUAGE plpgsql AS $$ BEGIN"
        + " INSERT INTO spi_it.marker(note) VALUES ('proc'); END $$");
    // 真 FUNCTION 返回 void(prokind 'f' → {call} 转义)
    jdbc.execute("CREATE FUNCTION spi_it.it_fn_void() RETURNS void LANGUAGE plpgsql AS $$ BEGIN"
        + " INSERT INTO spi_it.marker(note) VALUES ('fn'); END $$");
    // OUT refcursor 的 PROCEDURE,游标覆盖 50 行(测 maxRefCursorRows 截断)
    jdbc.execute(
        "CREATE PROCEDURE spi_it.it_proc_cursor(OUT c refcursor) LANGUAGE plpgsql AS $$ BEGIN"
            + " OPEN c FOR SELECT g FROM generate_series(1,50) g; END $$");
  }

  @AfterEach
  void tearDown() {
    jdbc.execute("DROP SCHEMA IF EXISTS spi_it CASCADE");
  }

  private StoredProcTaskExecutor executor(StoredProcExecutorProperties p) {
    return new StoredProcTaskExecutor(p, beanFactory, dataSource);
  }

  private StoredProcExecutorProperties props() {
    StoredProcExecutorProperties p = new StoredProcExecutorProperties();
    p.setEnabled(true);
    p.setForbidOsCapableRole(false); // testcontainers 是 superuser;OS 角色闸的拒绝路径单列 IT 验
    p.setDefaultAutoCommit(false); // 事务内,SET LOCAL search_path 生效
    p.setAllowedSchemas(Set.of("spi_it"));
    return p;
  }

  private TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "sp-job", "ti-1", "spi-node-1", params, Map.of());
  }

  @Test
  @DisplayName("目标为过程时应走原生调用形式, 并真实写入数据")
  void shouldInvokeNativeCall_whenTargetIsProcedure() {
    TaskResult r = executor(props()).execute(ctx(Map.of("procedureName", "spi_it.it_proc")));
    assertThat(r.success()).isTrue();
    assertThat(jdbc.queryForObject(
            "select count(*) from spi_it.marker where note='proc'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("目标为函数时应走转义调用形式, 并真实写入数据")
  void shouldInvokeCallEscape_whenTargetIsFunction() {
    TaskResult r = executor(props()).execute(ctx(Map.of("procedureName", "spi_it.it_fn_void")));
    assertThat(r.success()).isTrue();
    assertThat(jdbc.queryForObject(
            "select count(*) from spi_it.marker where note='fn'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  @DisplayName("游标结果超过上限时应标记截断")
  void shouldTruncateRefCursor_whenRowsExceedCap() {
    StoredProcExecutorProperties p = props();
    p.setMaxRefCursorRows(5);
    TaskResult r = executor(p)
        .execute(ctx(
            Map.of("procedureName", "spi_it.it_proc_cursor", "outParams", List.of("REF_CURSOR"))));
    assertThat(r.success()).isTrue();
    assertThat(r.output()).containsEntry("truncated", true);
  }

  @Test
  @DisplayName("固定检索路径后, 未限定模式的过程名应能解析并执行成功")
  void shouldResolveUnqualifiedName_whenSearchPathPinned() {
    // 非 qualified 名不在默认 search_path 里;靠 pin(SET LOCAL search_path = pg_catalog, spi_it)才能解析。
    // 成功即证明 pin 把 spi_it 加进了 search_path(否则 it_proc 找不到会报错)。
    StoredProcExecutorProperties p = props();
    p.setDefaultSchema("spi_it");
    p.setAllowedSchemas(Set.of("spi_it")); // 非 qualified 名按 defaultSchema(spi_it)判 schema 放行 + pin
    TaskResult r = executor(p).execute(ctx(Map.of("procedureName", "it_proc")));
    assertThat(r.success()).isTrue();
  }

  @Test
  @DisplayName("开启执行权限校验且当前用户具备权限时应放行")
  void shouldAllow_whenCurrentUserHasExecutePrivilege() {
    StoredProcExecutorProperties p = props();
    p.setVerifyExecutePrivilege(true);
    TaskResult r = executor(p).execute(ctx(Map.of("procedureName", "spi_it.it_proc")));
    assertThat(r.success()).isTrue(); // current_user 有 EXECUTE → has_function_privilege 通过
  }

  @Test
  @DisplayName("开启执行权限校验且当前用户无权限时应判失败, 并在消息中说明权限不足")
  void shouldReject_whenCurrentUserLacksExecutePrivilege() throws Exception {
    // 构造低权限角色 + 一个 REVOKE 掉 PUBLIC EXECUTE 的过程,用该角色单独连接执行:
    // verifyExecutePrivilege=true 时 has_function_privilege 应判定无权 → fail,message 含权限字样。
    String role = "spi_lowpriv_" + Long.toHexString(System.nanoTime());
    String pwd = "lp_pwd";
    jdbc.execute("CREATE PROCEDURE spi_it.it_proc_restricted() LANGUAGE plpgsql AS $$ BEGIN"
        + " INSERT INTO spi_it.marker(note) VALUES ('restricted'); END $$");
    // PG 过程默认对 PUBLIC 授予 EXECUTE;先收回再建低权限角色,使其确实无权。
    jdbc.execute("REVOKE EXECUTE ON PROCEDURE spi_it.it_proc_restricted() FROM PUBLIC");
    jdbc.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + pwd + "'");
    jdbc.execute("GRANT USAGE ON SCHEMA spi_it TO " + role);

    String baseUrl;
    try (Connection conn = dataSource.getConnection()) {
      baseUrl = conn.getMetaData().getURL();
    }

    DriverManagerDataSource lowPrivDs = new DriverManagerDataSource(baseUrl, role, pwd);
    try {
      StoredProcExecutorProperties p = props();
      p.setVerifyExecutePrivilege(true);
      StoredProcTaskExecutor exec = new StoredProcTaskExecutor(p, beanFactory, lowPrivDs);

      TaskResult r = exec.execute(ctx(Map.of("procedureName", "spi_it.it_proc_restricted")));

      assertThat(r.success()).isFalse();
      assertThat(r.message()).containsIgnoringCase("EXECUTE privilege");
    } finally {
      // 先回收授权(GRANT USAGE 会让角色被对象依赖,直接 DROP 会报 2BP01)。
      jdbc.execute("REVOKE ALL ON SCHEMA spi_it FROM " + role);
      jdbc.execute("DROP ROLE IF EXISTS " + role);
    }
  }

  @Test
  @DisplayName("开启角色校验时, 具备系统能力的高权限连接应被拒绝执行")
  void shouldReject_whenConnectionRoleHasOsCapability() {
    // testcontainers 连接是 superuser(OS 能力角色)→ forbidOsCapableRole=true 时代码层直接拒。
    StoredProcExecutorProperties p = props();
    p.setForbidOsCapableRole(true);
    TaskResult r = executor(p).execute(ctx(Map.of("procedureName", "spi_it.it_proc")));
    assertThat(r.success()).isFalse();
  }

  @Test
  @DisplayName("默认不允许定义者权限过程, 应拒绝调用以防越权提权")
  void shouldRejectSecurityDefiner_whenNotExplicitlyAllowed() {
    jdbc.execute(
        "CREATE PROCEDURE spi_it.it_proc_def() LANGUAGE plpgsql SECURITY DEFINER AS $$ BEGIN END"
            + " $$");
    // allowSecurityDefiner 默认 false → SECURITY DEFINER 过程被拒(防借 owner 提权)。
    TaskResult r = executor(props()).execute(ctx(Map.of("procedureName", "spi_it.it_proc_def")));
    assertThat(r.success()).isFalse();
  }

  @Test
  @DisplayName("显式允许后, 定义者权限过程应可正常调用")
  void shouldAllowSecurityDefiner_whenExplicitlyEnabled() {
    jdbc.execute(
        "CREATE PROCEDURE spi_it.it_proc_def2() LANGUAGE plpgsql SECURITY DEFINER AS $$ BEGIN END"
            + " $$");
    StoredProcExecutorProperties p = props();
    p.setAllowSecurityDefiner(true);
    TaskResult r = executor(p).execute(ctx(Map.of("procedureName", "spi_it.it_proc_def2")));
    assertThat(r.success()).isTrue();
  }
}
