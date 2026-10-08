package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

/**
 * 写路径端到端集成测试:POST /api/console/file-templates → batch.file_template_config。
 *
 * <p>守护:
 *
 * <ul>
 *   <li>合法 templateCode 写入数据库,charset/templateType/encryptType 字段透传
 *   <li>含空格 / 中文 templateCode → 400(@ValidResourceCode 拦截)
 *   <li>同 tenantId + templateCode 重复创建 → 唯一约束撞
 * </ul>
 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
@DisplayName("文件模板写路径: 编码格式校验,落库字段透传与唯一约束")
class ConsoleFileTemplateMutationIntegrationTest extends AbstractMutationIntegrationTest {

  private String body(String code) {
    return """
        {
          "tenantId": "int-ft-ta",
          "templateCode": "%s",
          "templateName": "integration test template",
          "templateType": "IMPORT",
          "bizType": "settlement",
          "fileFormatType": "DELIMITED",
          "charset": "UTF-8",
          "delimiter": ",",
          "encryptType": "NONE",
          "checksumType": "NONE",
          "compressType": "NONE",
          "streamingEnabled": false,
          "enabled": false,
          "version": 1
        }
        """.formatted(code).stripTrailing();
  }

  @Test
  @DisplayName("创建文件模板: 返回成功,落库的租户,编码,类型,字符集与加密方式与请求一致")
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

    var row = jdbcTemplate.queryForMap(
        "SELECT tenant_id, template_code, template_type, charset, encrypt_type"
            + " FROM batch.file_template_config WHERE template_code = ?",
        code);
    assertThat(row).containsEntry("tenant_id", "int-ft-ta");
    assertThat(row).containsEntry("template_code", code);
    assertThat(row).containsEntry("template_type", "IMPORT");
    assertThat(row).containsEntry("charset", "UTF-8");
    assertThat(row).containsEntry("encrypt_type", "NONE");

    jdbcTemplate.update("DELETE FROM batch.file_template_config WHERE template_code = ?", code);
  }

  @Test
  @DisplayName("编码含空格: 返回校验错误且不落库")
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

    Long cnt = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM batch.file_template_config WHERE template_code = ?",
        Long.class,
        "q q q");
    assertThat(cnt).isZero();
  }

  @Test
  @DisplayName("编码为中文: 返回请求不合法")
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
  @DisplayName("编码重复: 第二次写入失败,不产生重复模板")
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
