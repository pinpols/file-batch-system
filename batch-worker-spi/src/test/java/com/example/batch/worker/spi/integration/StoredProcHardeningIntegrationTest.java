package com.example.batch.worker.spi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.spi.task.TaskContext;
import com.example.batch.common.spi.task.TaskResult;
import com.example.batch.testing.AbstractIntegrationTest;
import com.example.batch.testing.OrchestratorWireMockSupport;
import com.example.batch.worker.spi.BatchWorkerSpiApplication;
import com.example.batch.worker.spi.storedproc.StoredProcExecutorProperties;
import com.example.batch.worker.spi.storedproc.StoredProcTaskExecutor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
    classes = BatchWorkerSpiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StoredProcHardeningIntegrationTest extends AbstractIntegrationTest {

  @DynamicPropertySource
  static void orchestratorStub(DynamicPropertyRegistry registry) {
    OrchestratorWireMockSupport.registerOrchestratorBaseUrls(registry);
  }

  @Autowired DataSource dataSource;
  @Autowired BeanFactory beanFactory;
  private JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("DROP SCHEMA IF EXISTS spi_it CASCADE");
    jdbc.execute("CREATE SCHEMA spi_it");
    jdbc.execute("CREATE TABLE spi_it.marker(id serial primary key, note text)");
    // 真 PROCEDURE(prokind 'p' → 原生 CALL)
    jdbc.execute(
        "CREATE PROCEDURE spi_it.it_proc() LANGUAGE plpgsql AS $$ BEGIN"
            + " INSERT INTO spi_it.marker(note) VALUES ('proc'); END $$");
    // 真 FUNCTION 返回 void(prokind 'f' → {call} 转义)
    jdbc.execute(
        "CREATE FUNCTION spi_it.it_fn_void() RETURNS void LANGUAGE plpgsql AS $$ BEGIN"
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
  void realProcedureRunsViaNativeCall() {
    TaskResult r = executor(props()).execute(ctx(Map.of("procedureName", "spi_it.it_proc")));
    assertThat(r.success()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from spi_it.marker where note='proc'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void realFunctionRunsViaCallEscape() {
    TaskResult r = executor(props()).execute(ctx(Map.of("procedureName", "spi_it.it_fn_void")));
    assertThat(r.success()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from spi_it.marker where note='fn'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void refCursorTruncatedAtCap() {
    StoredProcExecutorProperties p = props();
    p.setMaxRefCursorRows(5);
    TaskResult r =
        executor(p)
            .execute(
                ctx(
                    Map.of(
                        "procedureName",
                        "spi_it.it_proc_cursor",
                        "outParams",
                        List.of("REF_CURSOR"))));
    assertThat(r.success()).isTrue();
    assertThat(r.output().get("truncated")).isEqualTo(true);
  }

  @Test
  void searchPathPinResolvesUnqualifiedViaDefaultSchema() {
    // 非 qualified 名不在默认 search_path 里;靠 pin(SET LOCAL search_path = pg_catalog, spi_it)才能解析。
    // 成功即证明 pin 把 spi_it 加进了 search_path(否则 it_proc 找不到会报错)。
    StoredProcExecutorProperties p = props();
    p.setDefaultSchema("spi_it");
    p.setProcedureWhitelist(Set.of("it_proc")); // 非 qualified 需精确白名单放行
    p.setAllowedSchemas(Set.of()); // 关 schema 放行,逼走 whitelist + pin
    TaskResult r = executor(p).execute(ctx(Map.of("procedureName", "it_proc")));
    assertThat(r.success()).isTrue();
  }

  @Test
  void verifyExecutePrivilegeAllowsWhenCurrentUserHasExecute() {
    StoredProcExecutorProperties p = props();
    p.setVerifyExecutePrivilege(true);
    TaskResult r = executor(p).execute(ctx(Map.of("procedureName", "spi_it.it_proc")));
    assertThat(r.success()).isTrue(); // current_user 有 EXECUTE → has_function_privilege 通过
  }

  @Test
  void forbidOsCapableRoleRejectsSuperuserConnection() {
    // testcontainers 连接是 superuser(OS 能力角色)→ forbidOsCapableRole=true 时代码层直接拒。
    StoredProcExecutorProperties p = props();
    p.setForbidOsCapableRole(true);
    TaskResult r = executor(p).execute(ctx(Map.of("procedureName", "spi_it.it_proc")));
    assertThat(r.success()).isFalse();
  }

  @Test
  void securityDefinerProcedureRejectedByDefault() {
    jdbc.execute(
        "CREATE PROCEDURE spi_it.it_proc_def() LANGUAGE plpgsql SECURITY DEFINER AS $$ BEGIN END"
            + " $$");
    // allowSecurityDefiner 默认 false → SECURITY DEFINER 过程被拒(防借 owner 提权)。
    TaskResult r = executor(props()).execute(ctx(Map.of("procedureName", "spi_it.it_proc_def")));
    assertThat(r.success()).isFalse();
  }

  @Test
  void securityDefinerAllowedWhenExplicitlyEnabled() {
    jdbc.execute(
        "CREATE PROCEDURE spi_it.it_proc_def2() LANGUAGE plpgsql SECURITY DEFINER AS $$ BEGIN END"
            + " $$");
    StoredProcExecutorProperties p = props();
    p.setAllowSecurityDefiner(true);
    TaskResult r = executor(p).execute(ctx(Map.of("procedureName", "spi_it.it_proc_def2")));
    assertThat(r.success()).isTrue();
  }
}
