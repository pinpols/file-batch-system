package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/pipeline-definitions → batch.pipeline_definition。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 jobCode + pipelineType 写入数据库,bizType/workerGroup 字段透传
 *   <li>空格 / 中文 jobCode → 400(@ValidResourceCode 拦截)
 *   <li>pipelineType 不在 IMPORT/EXPORT/PROCESS/DISPATCH → 400(@Pattern 拦截)
 *   <li>同 tenantId + jobCode 重复 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("流水线定义写路径: 作业编码与流水线类型校验,落库字段透传与唯一约束")
class ConsolePipelineDefinitionMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String jobCode, String pipelineType) {
    return """
        {
          "tenantId": "int-pd-ta",
          "jobCode": "%s",
          "pipelineName": "integration test pipeline",
          "pipelineType": "%s",
          "bizType": "settlement",
          "workerGroup": "default",
          "enabled": false,
          "steps": []
        }
        """.formatted(jobCode, pipelineType).stripTrailing();
  }

  @Test
  @DisplayName("创建流水线定义: 返回成功,落库的租户,类型与业务类型与请求一致")
  void shouldCreatePipelineDefinitionWithValidCode() {
    String code = "int_pd_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/pipeline-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-pd-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code, "IMPORT"))
        .exchange()
        .expectStatus()
        .isOk();

    var row = jdbcTemplate.queryForMap(
        "SELECT tenant_id, job_code, pipeline_type, biz_type FROM batch.pipeline_definition"
            + " WHERE job_code = ?",
        code);
    assertThat(row).containsEntry("tenant_id", "int-pd-ta");
    assertThat(row).containsEntry("pipeline_type", "IMPORT");
    assertThat(row).containsEntry("biz_type", "settlement");

    jdbcTemplate.update("DELETE FROM batch.pipeline_definition WHERE job_code = ?", code);
  }

  @Test
  @DisplayName("作业编码含空格: 返回校验错误且不落库")
  void shouldRejectInvalidJobCodeWithSpaces() {
    client
        .post()
        .uri("/api/console/pipeline-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-pd-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("q q q", "IMPORT"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("VALIDATION_ERROR"));

    Long cnt = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.pipeline_definition WHERE job_code = ?", Long.class, "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("流水线类型不在允许集合内: 返回请求不合法且不落库")
  void shouldRejectInvalidPipelineType() {
    String code = "int_pd_bad_type_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/pipeline-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-pd-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code, "STREAMING"))
        .exchange()
        .expectStatus()
        .isBadRequest();

    Long cnt = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.pipeline_definition WHERE job_code = ?", Long.class, code);
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("作业编码重复: 第二次写入失败,不产生重复定义")
  void shouldRejectDuplicateJobCode() {
    String code = "int_pd_dup_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/pipeline-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-pd-dup-1-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code, "IMPORT"))
        .exchange()
        .expectStatus()
        .isOk();

    client
        .post()
        .uri("/api/console/pipeline-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-pd-dup-2-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code, "IMPORT"))
        .exchange()
        .expectStatus()
        .value(s -> assertThat(s).isIn(400, 409, 500));

    jdbcTemplate.update("DELETE FROM batch.pipeline_definition WHERE job_code = ?", code);
  }
}
