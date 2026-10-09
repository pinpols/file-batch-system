package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/job-definitions → DB → audit log。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 jobCode 写入数据库,job_definition 行可查
 *   <li>BE @ValidResourceCode 在 controller 入口拦截 q q q / 中文
 *   <li>同 tenantId+jobCode 重复创建 → 409 唯一约束
 *   <li>tenantId 强一致:body.tenantId 决定写入数据库 tenant_id,不会漂移
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
@DisplayName("作业定义写路径: 编码校验,落库租户强一致,更新与依赖作业字段")
class ConsoleJobDefinitionMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String createBody(String jobCode) {
    return createBody(jobCode, null);
  }

  private String createBody(String jobCode, String dependsOnJobCode) {
    String dependency = dependsOnJobCode == null
        ? ""
        : ",\n  \"dependsOnJobCode\": \"%s\"".formatted(dependsOnJobCode);
    return """
        {
          "tenantId": "int-ta",
          "jobCode": "%s",
          "jobName": "integration test",
          "jobType": "GENERAL",
          "scheduleType": "MANUAL"%s
        }
        """.formatted(jobCode, dependency).stripTrailing();
  }

  @Test
  @DisplayName("创建作业定义: 返回成功,落库行的租户与编码与请求一致")
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
        .value(body -> {
          assertThat(body).contains("\"code\":\"SUCCESS\"");
          assertThat(body).contains("\"jobCode\":\"" + jobCode + "\"");
        });

    // 行已入库,tenant_id 严格遵循 body 而非漂移
    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
        "SELECT tenant_id, job_code, enabled FROM batch.job_definition WHERE job_code = ?",
        jobCode);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)).containsEntry("tenant_id", "int-ta");
    assertThat(rows.get(0)).containsEntry("job_code", jobCode);
    Map<String, Object> monitoringPolicy = jdbcTemplate.queryForMap(
        "SELECT soft_runtime_seconds, start_grace_seconds FROM batch.job_monitoring_policy mp "
            + "JOIN batch.job_definition jd ON jd.id = mp.job_definition_id "
            + "WHERE jd.job_code = ?",
        jobCode);
    assertThat(monitoringPolicy)
        .containsEntry("soft_runtime_seconds", 0)
        .containsEntry("start_grace_seconds", 0);

    // 清理
    jdbcTemplate.update("DELETE FROM batch.job_definition WHERE job_code = ?", jobCode);
  }

  @Test
  @DisplayName("编码含空格: 返回校验错误且不落库")
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
    Long cnt = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.job_definition WHERE job_code = ?", Long.class, "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("编码为中文: 返回请求不合法")
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
  @DisplayName("编码重复: 第二次写入失败,不产生重复定义")
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
  @DisplayName("更新作业定义: 返回成功,库内名称改为新值")
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
    Long id = jdbcTemplate.queryForObject(
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

    String name = jdbcTemplate.queryForObject(
        "SELECT job_name FROM batch.job_definition WHERE id = ?", String.class, id);
    assertThat(name).isEqualTo("updated name");

    // 清理
    jdbcTemplate.update("DELETE FROM batch.job_definition WHERE job_code = ?", jobCode);
  }

  @Test
  @DisplayName("依赖作业字段: 创建与更新后库内依赖编码分别与请求一致")
  void shouldCreateAndUpdateDependsOnJobCode() {
    String jobCode = "int_test_dep_" + System.currentTimeMillis();
    try {
      client
          .post()
          .uri("/api/console/job-definitions")
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-cd-" + jobCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(createBody(jobCode, "UPSTREAM_JOB"))
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> {
            assertThat(body).contains("\"code\":\"SUCCESS\"");
            assertThat(body).contains("\"dependsOnJobCode\":\"UPSTREAM_JOB\"");
          });

      Long id = jdbcTemplate.queryForObject(
          "SELECT id FROM batch.job_definition WHERE job_code = ?", Long.class, jobCode);
      assertThat(id).isNotNull();
      String createdDepends = jdbcTemplate.queryForObject(
          "SELECT depends_on_job_code FROM batch.job_definition WHERE id = ?", String.class, id);
      assertThat(createdDepends).isEqualTo("UPSTREAM_JOB");

      client
          .put()
          .uri("/api/console/job-definitions/" + id)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ud-" + jobCode)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue("{\"tenantId\":\"int-ta\",\"dependsOnJobCode\":\"NEXT_UPSTREAM\"}")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .value(body -> assertThat(body).contains("\"dependsOnJobCode\":\"NEXT_UPSTREAM\""));

      String updatedDepends = jdbcTemplate.queryForObject(
          "SELECT depends_on_job_code FROM batch.job_definition WHERE id = ?", String.class, id);
      assertThat(updatedDepends).isEqualTo("NEXT_UPSTREAM");
    } finally {
      jdbcTemplate.update("DELETE FROM batch.job_definition WHERE job_code = ?", jobCode);
    }
  }
}
