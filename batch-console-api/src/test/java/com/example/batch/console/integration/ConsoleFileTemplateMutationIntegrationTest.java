package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.constants.CommonConstants;
import com.example.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/file-templates → batch.file_template_config。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 templateCode 落库,charset/templateType/encryptType 字段透传
 *   <li>含空格 / 中文 templateCode → 400(@ValidResourceCode 拦截)
 *   <li>同 tenantId + templateCode 重复创建 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleFileTemplateMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String code) {
    return "{"
        + "\"tenantId\":\"int-ft-ta\","
        + "\"templateCode\":\""
        + code
        + "\","
        + "\"templateName\":\"integration test template\","
        + "\"templateType\":\"IMPORT\","
        + "\"bizType\":\"settlement\","
        + "\"fileFormatType\":\"DELIMITED\","
        + "\"charset\":\"UTF-8\","
        + "\"delimiter\":\",\","
        + "\"encryptType\":\"NONE\","
        + "\"checksumType\":\"NONE\","
        + "\"compressType\":\"NONE\","
        + "\"streamingEnabled\":false,"
        + "\"enabled\":false,"
        + "\"version\":1"
        + "}";
  }

  @Test
  void shouldCreateFileTemplateWithValidCode() {
    String code = "int_ft_create_" + System.currentTimeMillis();

    client
        .post()
        .uri("/api/console/file-templates")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ft-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    var row =
        jdbcTemplate.queryForMap(
            "SELECT tenant_id, template_code, template_type, charset, encrypt_type"
                + " FROM batch.file_template_config WHERE template_code = ?",
            code);
    assertThat(row.get("tenant_id")).isEqualTo("int-ft-ta");
    assertThat(row.get("template_code")).isEqualTo(code);
    assertThat(row.get("template_type")).isEqualTo("IMPORT");
    assertThat(row.get("charset")).isEqualTo("UTF-8");
    assertThat(row.get("encrypt_type")).isEqualTo("NONE");

    jdbcTemplate.update("DELETE FROM batch.file_template_config WHERE template_code = ?", code);
  }

  @Test
  void shouldRejectInvalidTemplateCodeWithSpaces() {
    client
        .post()
        .uri("/api/console/file-templates")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ft-bad-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("q q q"))
        .exchange()
        .expectStatus()
        .isBadRequest()
        .expectBody(String.class)
        .value(b -> assertThat(b).contains("VALIDATION_ERROR"));

    Long cnt =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM batch.file_template_config WHERE template_code = ?",
            Long.class,
            "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  void shouldRejectChineseTemplateCode() {
    client
        .post()
        .uri("/api/console/file-templates")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ft-bad-2")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body("中文模板"))
        .exchange()
        .expectStatus()
        .isBadRequest();
  }

  @Test
  void shouldRejectDuplicateTemplateCode() {
    String code = "int_ft_dup_" + System.currentTimeMillis();
    client
        .post()
        .uri("/api/console/file-templates")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ft-dup-1-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .isOk();

    client
        .post()
        .uri("/api/console/file-templates")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "idem-ft-dup-2-" + code)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body(code))
        .exchange()
        .expectStatus()
        .value(s -> assertThat(s).isIn(400, 409, 500));

    jdbcTemplate.update("DELETE FROM batch.file_template_config WHERE template_code = ?", code);
  }
}
