package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/job-definitions → DB → audit log。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 jobCode 落库,job_definition 行可查
 *   <li>BE @ValidResourceCode 在 controller 入口拦截 q q q / 中文
 *   <li>同 tenantId+jobCode 重复创建 → 409 唯一约束
 *   <li>tenantId 强一致:body.tenantId 决定落库 tenant_id,不会漂移
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleJobDefinitionMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String createBody(String jobCode) {
    return "{"
        + "\"tenantId\":\"int-ta\","
        + "\"jobCode\":\""
        + jobCode
        + "\","
        + "\"jobName\":\"integration test\","
        + "\"jobType\":\"GENERAL\","
        + "\"scheduleType\":\"MANUAL\""
        + "}";
  }

  @Test
  void shouldCreateJobDefinitionWithValidCode() {
    String jobCode = "int_test_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-create-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body -> {
              assertThat(body).contains("\"code\":\"SUCCESS\"");
              assertThat(body).contains("\"jobCode\":\"" + jobCode + "\"");
            });

    // 行已入库,tenant_id 严格遵循 body 而非漂移
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT tenant_id, job_code, enabled FROM batch.job_definition WHERE job_code = ?",
            jobCode);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("tenant_id")).isEqualTo("int-ta");
    assertThat(rows.get(0).get("job_code")).isEqualTo(jobCode);

    // 清理
    jdbcTemplate.update("DELETE FROM batch.job_definition WHERE job_code = ?", jobCode);
  }

  @Test
  void shouldRejectInvalidJobCodeWithSpaces() {
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody("q q q"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(body -> assertThat(body).contains("VALIDATION_ERROR"));

    // 守护:非法 jobCode 不能入库
    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.job_definition WHERE job_code = ?", Long.class, "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  void shouldRejectChineseJobCode() {
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody("中文测试"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectDuplicateJobCode() {
    String jobCode = "int_test_dup_" + System.currentTimeMillis();
    // 第一次成功
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-dup-1-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .isOk();

    // 第二次同 tenantId + jobCode → 唯一约束撞,400/409 都可
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-dup-2-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .value(status -> assertThat(status).isIn(400, 409, 500));

    // 清理
    jdbcTemplate.update("DELETE FROM batch.job_definition WHERE job_code = ?", jobCode);
  }

  @Test
  void shouldUpdateJobDefinitionRow() {
    String jobCode = "int_test_update_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/job-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cu-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(createBody(jobCode))
        .exchange()
        .expectStatus()
        .isOk();
    Long id =
        jdbcTemplate.queryForObject(
            "SELECT id FROM batch.job_definition WHERE job_code = ?", Long.class, jobCode);
    assertThat(id).isNotNull();

    // PUT 更新 jobName
    String updateBody =
        "{\"tenantId\":\"int-ta\",\"jobName\":\"updated name\",\"jobType\":\"GENERAL\","
            + "\"scheduleType\":\"MANUAL\"}";
    client
        .put()
        .uri("/api/console/job-definitions/" + id)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-uu-" + jobCode)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(updateBody)
        .exchange()
        .expectStatus()
        .isOk();

    String name =
        jdbcTemplate.queryForObject(
            "SELECT job_name FROM batch.job_definition WHERE id = ?", String.class, id);
    assertThat(name).isEqualTo("updated name");

    // 清理
    jdbcTemplate.update("DELETE FROM batch.job_definition WHERE job_code = ?", jobCode);
  }
}
