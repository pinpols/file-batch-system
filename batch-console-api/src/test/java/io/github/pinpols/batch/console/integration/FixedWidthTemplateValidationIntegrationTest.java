package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.constants.CommonConstants;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.domain.file.infrastructure.DefaultConsoleFileTemplateApplicationService;
import io.github.pinpols.batch.console.domain.file.mapper.FileTemplateConfigMapper;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("定长文件模板启用与写入的数据库校验")
class FixedWidthTemplateValidationIntegrationTest extends AbstractMutationIntegrationTest {
  private static final String INVALID = "[{\"target\":\"name\",\"start\":1.5,\"length\":2}]";
  private static final String VALID = "[{\"target\":\"name\",\"start\":1,\"length\":2}]";

  @Autowired
  private FileTemplateConfigMapper realMapper;

  @Autowired
  private ObjectMapper objectMapper;

  @Test
  @DisplayName("空映射骨架允许启用")
  void shouldEnableTemplate_whenSkeletonMappingIsNull() {
    String suffix = Long.toString(System.nanoTime());
    String code = "fixed_empty_" + suffix;
    create(code, false, VALID, "create-empty-" + suffix).expectStatus().isOk();
    Long id = idOf(code);
    String clearSql = "update batch.file_template_config set field_mappings = null where id = ?";
    jdbcTemplate.update(clearSql, id);
    client
        .patch()
        .uri("/api/console/file-templates/" + id)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "enable-empty-" + suffix)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", "int-ft-ta", "enabled", true))
        .exchange()
        .expectStatus()
        .isOk();
    String enabledSql = "select enabled from batch.file_template_config where id = ?";
    assertThat(jdbcTemplate.queryForObject(enabledSql, Boolean.class, id)).isTrue();
  }

  @Test
  @DisplayName("有效映射未变化时允许启用")
  void shouldEnableTemplate_whenValidMappingIsUnchanged() {
    String suffix = Long.toString(System.nanoTime());
    String code = "fixed_enable_" + suffix;
    create(code, false, VALID, "create-enable-" + suffix).expectStatus().isOk();
    Long id = idOf(code);
    client
        .patch()
        .uri("/api/console/file-templates/" + id)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "enable-valid-" + suffix)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", "int-ft-ta", "enabled", true))
        .exchange()
        .expectStatus()
        .isOk();
    String enabledSql = "select enabled from batch.file_template_config where id = ?";
    assertThat(jdbcTemplate.queryForObject(enabledSql, Boolean.class, id)).isTrue();
  }

  @Test
  @DisplayName("启用前映射发生变化时拒绝写入")
  void shouldRejectEnable_whenMappingChangesAfterValidation() {
    String suffix = Long.toString(System.nanoTime());
    String code = "fixed_race_" + suffix;
    create(code, false, VALID, "create-race-" + suffix).expectStatus().isOk();
    Long id = idOf(code);
    FileTemplateConfigMapper interleavedMapper =
        mock(FileTemplateConfigMapper.class, invocation -> {
          if (invocation.getMethod().getName().startsWith("toggleEnabled")) {
            String updateSql =
                "update batch.file_template_config set field_mappings = ?::jsonb where id = ?";
            jdbcTemplate.update(updateSql, INVALID, id);
          }
          try {
            return invocation.getMethod().invoke(realMapper, invocation.getArguments());
          } catch (InvocationTargetException ex) {
            throw ex.getCause();
          }
        });
    ConsoleTenantGuard tenantGuard = mock(ConsoleTenantGuard.class);
    when(tenantGuard.resolveTenant("int-ft-ta")).thenReturn("int-ft-ta");
    var service = new DefaultConsoleFileTemplateApplicationService(
        interleavedMapper, tenantGuard, mock(ConsoleRequestMetadataResolver.class), objectMapper);

    assertThatThrownBy(() -> service.toggle(id, "int-ft-ta", true))
        .isInstanceOf(BizException.class);
    String enabledSql = "select enabled from batch.file_template_config where id = ?";
    Boolean enabled = jdbcTemplate.queryForObject(enabledSql, Boolean.class, id);
    assertThat(enabled).isFalse();
    assertThat(mappingStart(code)).isEqualTo("1.5");
  }

  @Test
  @DisplayName("无效启用模板的创建更新和启用不改变已存数据")
  void shouldPreserveStoredValues_whenInvalidTemplateIsWritten() {
    String suffix = Long.toString(System.nanoTime());
    String rejectedCode = "fixed_reject_" + suffix;
    create(rejectedCode, true, INVALID, "create-bad-" + suffix).expectStatus().isBadRequest();
    String countSql = "select count(*) from batch.file_template_config where template_code = ?";
    Integer count = jdbcTemplate.queryForObject(countSql, Integer.class, rejectedCode);
    assertThat(count).isZero();

    String validCode = "fixed_valid_" + suffix;
    create(validCode, true, VALID, "create-good-" + suffix).expectStatus().isOk();
    Long validId = idOf(validCode);
    client
        .put()
        .uri("/api/console/file-templates/" + validId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "update-bad-" + suffix)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", "int-ft-ta", "fieldMappingsJson", INVALID))
        .exchange()
        .expectStatus()
        .isBadRequest();
    assertThat(mappingStart(validCode)).isEqualTo("1");

    String draftCode = "fixed_draft_" + suffix;
    create(draftCode, false, INVALID, "create-draft-" + suffix).expectStatus().isOk();
    Long draftId = idOf(draftCode);
    client
        .patch()
        .uri("/api/console/file-templates/" + draftId)
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, "enable-bad-" + suffix)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of("tenantId", "int-ft-ta", "enabled", true))
        .exchange()
        .expectStatus()
        .isBadRequest();
    String enabledSql = "select enabled from batch.file_template_config where id = ?";
    Boolean enabled = jdbcTemplate.queryForObject(enabledSql, Boolean.class, draftId);
    assertThat(enabled).isFalse();
  }

  private WebTestClient.ResponseSpec create(
      String code, boolean enabled, String mapping, String idempotencyKey) {
    return client
        .post()
        .uri("/api/console/file-templates")
        .header(CommonConstants.DEFAULT_IDEMPOTENCY_KEY_HEADER, idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(Map.of(
            "tenantId",
            "int-ft-ta",
            "templateCode",
            code,
            "templateName",
            "probe",
            "templateType",
            "IMPORT",
            "bizType",
            "settlement",
            "fileFormatType",
            "FIXED_WIDTH",
            "charset",
            "UTF-8",
            "enabled",
            enabled,
            "fieldMappingsJson",
            mapping))
        .exchange();
  }

  private Long idOf(String code) {
    String idSql = "select id from batch.file_template_config where template_code = ?";
    return jdbcTemplate.queryForObject(idSql, Long.class, code);
  }

  private String mappingStart(String code) {
    String mappingStartSql =
        "select field_mappings -> 0 ->> 'start' from batch.file_template_config where template_code = ?";
    return jdbcTemplate.queryForObject(mappingStartSql, String.class, code);
  }
}
