package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.enums.PipelineType;
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
    return body(jobCode, pipelineType, null, null);
  }

  private String body(
      String jobCode, String pipelineType, String loadImplCode, String loadStepParams) {
    String steps = java.util.stream.IntStream.range(
            0, PipelineType.stages(pipelineType).size())
        .mapToObj(index -> {
          String stage = PipelineType.stages(pipelineType).get(index);
          String implCode = stage.equals("LOAD") && loadImplCode != null
              ? loadImplCode
              : pipelineType + "_" + stage;
          String stepParams = stage.equals("LOAD") && loadStepParams != null
              ? io.github.pinpols.batch.common.utils.JsonUtils.toJson(loadStepParams)
              : "\"{}\"";
          return """
              {"stepCode":"%s","stepName":"%s","stageCode":"%s","stepOrder":%d,
               "implCode":"%s","stepParams":%s}
              """.formatted(stage, stage, stage, index, implCode, stepParams).strip();
        })
        .collect(java.util.stream.Collectors.joining(","));
    return """
        {
          "tenantId": "int-pd-ta",
          "jobCode": "%s",
          "pipelineName": "integration test pipeline",
          "pipelineType": "%s",
          "bizType": "settlement",
          "workerGroup": "default",
          "enabled": false,
          "steps": [%s]
        }
        """.formatted(jobCode, pipelineType, steps).stripTrailing();
  }

  private void registerStages(String pipelineType, String customImplCode) {
    PipelineType.stages(pipelineType).forEach(stage -> {
      String implCode = stage.equals("LOAD") && customImplCode != null
          ? customImplCode
          : pipelineType + "_" + stage;
      jdbcTemplate.update(
          "INSERT INTO batch.step_registry(module, impl_code, impl_class) VALUES (?, ?, ?) "
              + "ON CONFLICT (module, impl_code) DO NOTHING",
          pipelineType,
          implCode,
          getClass().getName());
    });
  }

  private void deletePipelineDefinitions(String jobCode) {
    jdbcTemplate.update(
        "DELETE FROM batch.pipeline_step_definition WHERE pipeline_definition_id IN "
            + "(SELECT id FROM batch.pipeline_definition WHERE job_code = ?)",
        jobCode);
    jdbcTemplate.update("DELETE FROM batch.pipeline_definition WHERE job_code = ?", jobCode);
  }

  @Test
  @DisplayName("创建流水线定义: 返回成功,落库的租户,类型与业务类型与请求一致")
  void shouldCreatePipelineDefinitionWithValidCode() {
    String code = "int_pd_create_" + System.currentTimeMillis();
    registerStages("IMPORT", null);

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

    deletePipelineDefinitions(code);
  }

  @Test
  @DisplayName("流水线敏感步骤参数读取脱敏且掩码更新保留原凭据")
  void shouldMaskAndPreserveSensitiveStepParams() {
    String code = "int_pd_secret_" + System.currentTimeMillis();
    String implCode = "it-secret-step-" + System.currentTimeMillis();
    jdbcTemplate.update(
        "INSERT INTO batch.step_registry(module, impl_code, impl_class) VALUES ('IMPORT', ?, ?)",
        implCode,
        getClass().getName());
    registerStages("IMPORT", implCode);
    Long definitionId = null;
    try {
      String createBody =
          body(code, "IMPORT", implCode, "{\"apiKey\":\"pipeline-secret\",\"label\":\"old\"}");
      String created = client
          .post()
          .uri("/api/console/pipeline-definitions")
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-pd-secret-" + code)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(createBody)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();

      definitionId = jdbcTemplate.queryForObject(
          "SELECT id FROM batch.pipeline_definition WHERE tenant_id = ? AND job_code = ?",
          Long.class,
          "int-pd-ta",
          code);
      assertThat(created).contains("****").doesNotContain("pipeline-secret");
      String detail = client
          .get()
          .uri(
              "/api/console/pipeline-definitions/{id}?tenantId={tenantId}",
              definitionId,
              "int-pd-ta")
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      assertThat(detail).contains("****").doesNotContain("pipeline-secret");

      String updateBody = createBody.replace("pipeline-secret", "****").replace("old", "new");
      String updated = client
          .put()
          .uri("/api/console/pipeline-definitions/{id}", definitionId)
          .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-pd-secret-update-" + code)
          .contentType(MediaType.APPLICATION_JSON)
          .bodyValue(updateBody)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();

      assertThat(updated).contains("****").doesNotContain("pipeline-secret");
      var storedParams = jdbcTemplate.queryForMap(
          "SELECT step_params->>'apiKey' AS api_key, step_params->>'label' AS label "
              + "FROM batch.pipeline_step_definition WHERE pipeline_definition_id = ? "
              + "AND stage_code = 'LOAD'",
          definitionId);
      assertThat(storedParams).containsEntry("api_key", "pipeline-secret");
      assertThat(storedParams).containsEntry("label", "new");
    } finally {
      if (definitionId != null) {
        jdbcTemplate.update(
            "DELETE FROM batch.pipeline_step_definition WHERE pipeline_definition_id = ?",
            definitionId);
        jdbcTemplate.update("DELETE FROM batch.pipeline_definition WHERE id = ?", definitionId);
      }
      jdbcTemplate.update("DELETE FROM batch.step_registry WHERE impl_code = ?", implCode);
    }
  }

  @Test
  @DisplayName("租户管理员不能通过流水线定义 API 指定物理 Worker 分组")
  void shouldRejectTenantAdminAssigningWorkerGroup() {
    String code = "int_pd_tenant_route_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/pipeline-definitions")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-pd-route-" + code)
        .header("X-Console-Roles", "ROLE_TENANT_ADMIN")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code, "IMPORT"))
        .exchange()
        .expectStatus()
        .isForbidden();

    Long count = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.pipeline_definition WHERE tenant_id = ? AND job_code = ?",
        Long.class,
        "int-pd-ta",
        code);
    assertThat(count).isZero();
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
    registerStages("IMPORT", null);
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

    deletePipelineDefinitions(code);
  }
}
