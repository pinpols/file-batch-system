package io.github.pinpols.batch.orchestrator.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.orchestrator.BatchOrchestratorApplication;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Orchestrator 内部测试数据清理接口的真 DB 验证。 */
@SpringBootTest(
    classes = BatchOrchestratorApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true"})
class AdminTestDataCleanupControllerIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbcTemplate;
  private RestClient restClient;

  private static final String PREFIX = "itadmin";
  private static final String NEIGHBOR = "itadmin2-job";

  @BeforeEach
  void setUp() {
    restClient = RestClient.builder().baseUrl("http://127.0.0.1:" + port).build();
    cleanup();
    seed();
  }

  @AfterEach
  void tearDown() {
    cleanup();
  }

  private void cleanup() {
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
    jdbcTemplate.update(
        "INSERT INTO batch.job_definition (tenant_id, job_code, job_name, job_type, biz_type,"
            + " schedule_type, timezone, trigger_mode, queue_code, worker_group, window_code,"
            + " priority, enabled, created_at, updated_at) VALUES"
            + " ('t1','itadmin2-job','邻近不该被删','GENERAL','TEST','MANUAL','Asia/Shanghai',"
            + " 'SCHEDULED','q','IMPORT','always_open',5,true,now(),now())");
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
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM batch.job_definition WHERE job_code LIKE 'itadmin-%'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM batch.job_definition WHERE job_code = ?",
                Integer.class,
                NEIGHBOR))
        .isEqualTo(1);

    Map<String, Integer> response =
        restClient
            .delete()
            .uri("/internal/admin/test-data?prefix=" + PREFIX)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
    assertThat(response).containsEntry("job_definition", 1).containsEntry("workflow_definition", 1);

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
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*)::int FROM batch.job_definition WHERE job_code = ?",
                Integer.class,
                NEIGHBOR))
        .isEqualTo(1);
  }
}
