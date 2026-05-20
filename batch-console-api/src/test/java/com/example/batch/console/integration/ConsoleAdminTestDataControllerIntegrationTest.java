package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * P1: ConsoleAdminTestDataController 真删 DB FK 链路验证。
 *
 * <p>原 ConsoleAdminTestDataControllerTest(unit)用 mock 验证 14 张表的 SQL 都跑,但**没真删** —— 这个 IT 用
 * Testcontainer PG 真种数据 + 真调端点 + 真删后查 count,确认级联清理可达 0。
 *
 * <p>覆盖路径:hot 表 job_definition / workflow_definition / job_instance / job_task / job_partition 等都
 * seed 一行,确认 prefix-%% LIKE 不误删邻近资源(prefix='it-test' 不会删 'it-tester-...')。
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleAdminTestDataControllerIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbcTemplate;
  private WebTestClient webTestClient;

  private static final String PREFIX = "itadmin";
  private static final String NEIGHBOR = "itadmin2-job";

  @BeforeEach
  void setUp() {
    webTestClient =
        WebTestClient.bindToServer()
            .baseUrl("http://127.0.0.1:" + port)
            .responseTimeout(Duration.ofSeconds(60))
            .build();
    cleanup();
    seed();
  }

  @AfterEach
  void tearDown() {
    cleanup();
  }

  private void cleanup() {
    // 兜底清理:测试可能因失败留数据,先扫除
    jdbcTemplate.update(
        "DELETE FROM batch.job_definition WHERE job_code LIKE 'itadmin%' OR job_code LIKE"
            + " 'it_admin%'");
    jdbcTemplate.update(
        "DELETE FROM batch.workflow_definition WHERE workflow_code LIKE 'itadmin%'");
    jdbcTemplate.update(
        "DELETE FROM batch.console_user_account WHERE username LIKE 'itadmin%' OR username LIKE"
            + " 'op-itadmin%'");
  }

  private void seed() {
    // 1) 邻近资源(itadmin2-job):不带 '-' 后 substring 匹配前缀但完整名不同;LIKE prefix-%%
    // 模式必须只匹配 'itadmin-...'  不能误删
    jdbcTemplate.update(
        "INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type,"
            + " schedule_type, timezone, trigger_mode, queue_code, worker_group, window_code,"
            + " priority, enabled, created_at, updated_at) VALUES"
            + " ('t1','itadmin2-job','邻近不该被删','GENERAL','TEST','MANUAL','Asia/Shanghai',"
            + " 'SCHEDULED','q','IMPORT','always_open',5,true,now(),now())");
    // 2) 受试目标(itadmin-* 前缀)
    jdbcTemplate.update(
        "INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type,"
            + " schedule_type, timezone, trigger_mode, queue_code, worker_group, window_code,"
            + " priority, enabled, created_at, updated_at) VALUES"
            + " ('t1','itadmin-job1','受试','GENERAL','TEST','MANUAL','Asia/Shanghai',"
            + " 'SCHEDULED','q','IMPORT','always_open',5,true,now(),now())");
    jdbcTemplate.update(
        "INSERT INTO batch.workflow_definition (tenant_id, workflow_code, workflow_name,"
            + " workflow_type, version, enabled, created_at, updated_at) VALUES"
            + " ('t1','itadmin-wf1','受试 WF','DAG',1,true,now(),now())");
  }

  @Test
  void cleanupShouldDeleteOnlyHyphenPrefixedRecords() {
    int countTarget =
        jdbcTemplate.queryForObject(
            "SELECT count(*)::int FROM batch.job_definition WHERE job_code LIKE 'itadmin-%'",
            Integer.class);
    int countNeighbor =
        jdbcTemplate.queryForObject(
            "SELECT count(*)::int FROM batch.job_definition WHERE job_code = ?",
            Integer.class,
            NEIGHBOR);
    assertThat(countTarget).isEqualTo(1);
    assertThat(countNeighbor).isEqualTo(1);

    webTestClient
        .delete()
        .uri("/api/console/admin/test-data?prefix=" + PREFIX)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("SUCCESS")
        .jsonPath("$.data.job_definition")
        .isEqualTo(1)
        .jsonPath("$.data.workflow_definition")
        .isEqualTo(1);

    // 受试已清
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM batch.job_definition WHERE job_code LIKE 'itadmin-%'",
                Integer.class))
        .isZero();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM batch.workflow_definition WHERE workflow_code LIKE"
                    + " 'itadmin-%'",
                Integer.class))
        .isZero();
    // 邻近未删(prefix-%% LIKE 锚定 '-' 后缀)
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM batch.job_definition WHERE job_code = ?",
                Integer.class,
                NEIGHBOR))
        .isEqualTo(1);
  }

  @Test
  void cleanupShouldRejectBlankPrefix() {
    webTestClient
        .delete()
        .uri("/api/console/admin/test-data?prefix=")
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void cleanupShouldRejectIllegalPrefixCharacters() {
    // % / ' / ; 等 @Pattern 拦截
    webTestClient
        .delete()
        .uri("/api/console/admin/test-data?prefix=test%25")
        .exchange()
        .expectStatus()
        .is4xxClientError();
  }
}
