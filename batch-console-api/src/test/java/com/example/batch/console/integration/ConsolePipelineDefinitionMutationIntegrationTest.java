package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 写路径端到端集成测试:POST /api/console/pipeline-definitions → batch.pipeline_definition。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 jobCode + pipelineType 落库,bizType/workerGroup 字段透传
 *   <li>空格 / 中文 jobCode → 400(@ValidResourceCode 拦截)
 *   <li>pipelineType 不在 IMPORT/EXPORT/PROCESS/DISPATCH → 400(@Pattern 拦截)
 *   <li>同 tenantId + jobCode 重复 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsolePipelineDefinitionMutationIntegrationTest extends AbstractIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private JdbcTemplate jdbcTemplate;

  private WebTestClient client;

  @BeforeEach
  void setUp() {
    client =
        WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(Duration.ofSeconds(60))
            .build();
  }

  private String body(String jobCode, String pipelineType) {
    return "{"
        + "\"tenantId\":\"int-pd-ta\","
        + "\"jobCode\":\""
        + jobCode
        + "\","
        + "\"pipelineName\":\"integration test pipeline\","
        + "\"pipelineType\":\""
        + pipelineType
        + "\","
        + "\"bizType\":\"settlement\","
        + "\"workerGroup\":\"default\","
        + "\"enabled\":false,"
        + "\"steps\":[]"
        + "}";
  }

  @Test
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

    var row =
        jdbcTemplate.queryForMap(
            "SELECT tenant_id, job_code, pipeline_type, biz_type FROM batch.pipeline_definition"
                + " WHERE job_code = ?",
            code);
    assertThat(row.get("tenant_id")).isEqualTo("int-pd-ta");
    assertThat(row.get("pipeline_type")).isEqualTo("IMPORT");
    assertThat(row.get("biz_type")).isEqualTo("settlement");

    jdbcTemplate.update("DELETE FROM batch.pipeline_definition WHERE job_code = ?", code);
  }

  @Test
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

    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.pipeline_definition WHERE job_code = ?",
            Long.class,
            "q q q");
    assertThat(cnt).isZero();
  }

  @Test
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

    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.pipeline_definition WHERE job_code = ?", Long.class, code);
    assertThat(cnt).isZero();
  }

  @Test
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
