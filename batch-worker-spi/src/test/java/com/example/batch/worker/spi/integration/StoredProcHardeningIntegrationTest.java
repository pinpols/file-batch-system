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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SKELETON — DB 相关 hardening 行为的真 testcontainers PG 集成测试。这些行为无法在 mockito 单测里真测,只能打真库:
 *
 * <ul>
 *   <li>{@code SET LOCAL search_path}:pin 后过程解析目标固定,session search_path 注入不生效
 *   <li>{@code has_function_privilege(...)}:verifyExecutePrivilege=true 时无权过程被拒
 *   <li>{@code pg_proc.prokind}:真 PROCEDURE 走原生 CALL,FUNCTION 走 {call} 转义
 *   <li>{@code pg_proc.prosecdef}:SECURITY DEFINER 过程的 prosecdef 感知(本 PR 仅属性+文档)
 *   <li>真 REFCURSOR 在 maxRefCursorRows 下的截断 + fetchSize 行为
 * </ul>
 *
 * <p>模板照搬 {@link SqlTaskExecutorIntegrationTest}。整个类 {@link Disabled} —— 当前不在本仓库运行, 待 DDL fixture(建
 * schema/proc/function/refcursor + 低权限角色)落地后启用。
 */
@Disabled("IT skeleton — requires testcontainers PG + DDL fixtures; not run in this PR")
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

  @BeforeEach
  void fixtures() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    // TODO: create schema `batch`, a real PROCEDURE, a FUNCTION returning refcursor,
    // a SECURITY DEFINER proc, and a low-privilege role lacking EXECUTE.
    jdbc.execute("CREATE SCHEMA IF NOT EXISTS batch");
    // jdbc.execute("CREATE PROCEDURE batch.touch() LANGUAGE sql AS $$ SELECT 1 $$");
    // jdbc.execute(
    //     "CREATE FUNCTION batch.big_cursor() RETURNS refcursor LANGUAGE plpgsql AS $$ ... $$");
  }

  private StoredProcTaskExecutor executor(StoredProcExecutorProperties props) {
    props.setEnabled(true);
    return new StoredProcTaskExecutor(props, beanFactory, dataSource);
  }

  private TaskContext ctx(Map<String, Object> params) {
    return new TaskContext("t1", "sp-job", "ti-1", "spi-node-1", params, Map.of());
  }

  @Test
  void nativeCallForRealProcedure() {
    StoredProcExecutorProperties props = new StoredProcExecutorProperties();
    props.setAllowedSchemas(Set.of("batch"));
    TaskResult r = executor(props).execute(ctx(Map.of("procedureName", "batch.touch")));
    assertThat(r.success()).isTrue();
  }

  @Test
  void searchPathPinnedToDefaultSchema() {
    // 即使会话 search_path 指向别处,pin 后 batch.touch 仍应解析到 batch schema 的过程
    StoredProcExecutorProperties props = new StoredProcExecutorProperties();
    props.setAllowedSchemas(Set.of("batch"));
    props.setDefaultSchema("batch");
    TaskResult r = executor(props).execute(ctx(Map.of("procedureName", "touch")));
    assertThat(r.success()).isTrue();
  }

  @Test
  void verifyExecutePrivilegeRejectsWhenLackingGrant() {
    // 连低权限角色 datasource → has_function_privilege 返回 false → 拒绝
    StoredProcExecutorProperties props = new StoredProcExecutorProperties();
    props.setAllowedSchemas(Set.of("batch"));
    props.setVerifyExecutePrivilege(true);
    TaskResult r = executor(props).execute(ctx(Map.of("procedureName", "batch.touch")));
    assertThat(r.success()).isFalse();
    assertThat(r.message()).contains("EXECUTE privilege");
  }

  @Test
  void refCursorTruncatedAgainstRealCursor() {
    StoredProcExecutorProperties props = new StoredProcExecutorProperties();
    props.setAllowedSchemas(Set.of("batch"));
    props.setMaxRefCursorRows(5);
    TaskResult r =
        executor(props)
            .execute(
                ctx(
                    Map.of(
                        "procedureName", "batch.big_cursor", "outParams", List.of("REF_CURSOR"))));
    assertThat(r.success()).isTrue();
    assertThat(r.output().get("truncated")).isEqualTo(true);
  }
}
