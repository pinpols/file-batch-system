package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.i18n.LocalizedErrorRenderer;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.ops.ConsoleOrchestratorPort;
import io.github.pinpols.batch.console.domain.file.application.contract.query.FileErrorRecordQueryRequest;
import io.github.pinpols.batch.console.domain.file.infrastructure.query.ConsoleFileQueryService;
import io.github.pinpols.batch.console.domain.file.mapper.FileRecordMapper;
import io.github.pinpols.batch.console.domain.file.support.ConsoleFileQueryMappers;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("文件详情响应的凭据脱敏和租户隔离")
class ConsoleFileRecordDetailSecurityIntegrationTest extends AbstractMutationIntegrationTest {
  private final FileRecordMapper fileRecordMapper;
  private final ConsoleFileQueryMappers fileQueryMappers;
  private final LocalizedErrorRenderer localizedErrorRenderer;

  @Autowired
  ConsoleFileRecordDetailSecurityIntegrationTest(
      FileRecordMapper fileRecordMapper,
      ConsoleFileQueryMappers fileQueryMappers,
      LocalizedErrorRenderer localizedErrorRenderer) {
    this.fileRecordMapper = fileRecordMapper;
    this.fileQueryMappers = fileQueryMappers;
    this.localizedErrorRenderer = localizedErrorRenderer;
  }

  @Test
  @DisplayName("跨文件错误列表使用真实数据库中的各自模板脱敏策略")
  void shouldApplyEachFileMaskingPolicyFromDatabase() {
    final String insertTemplateSql = """
        insert into batch.file_template_config
          (tenant_id, template_code, template_name, template_type, file_format_type,
           error_line_masking_enabled, preview_masking_enabled, masking_rule_set)
        values (?, ?, ?, 'IMPORT', 'DELIMITED', ?, false, 'STRICT')
        """;
    final String insertFileSql = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
           source_type, file_status, metadata_json)
        values (?, 'INPUT', ?, 'DELIMITED', 'LOCAL', ?, 'SYSTEM', 'RECEIVED', ?::jsonb)
        returning id
        """;
    final String insertErrorSql = """
        insert into batch.file_error_record
          (tenant_id, file_id, error_code, error_key, error_args, error_message, raw_record)
        values (?, ?, ?, 'error.common.conflict_detail', '["1234567890"]'::jsonb,
                'fallback 1234567890', '{"name":"Alice Smith","nested":{"address":"Main Street"},"phone":"1234567890"}'::jsonb)
        """;
    final String insertHistoricalErrorSql = """
        insert into batch.file_error_record (tenant_id, file_id, error_code, raw_record)
        values (?, ?, 'HISTORICAL_ERROR', to_jsonb('{"name":"Historical Alice"}'::text))
        """;
    String tenant = "policy-mask-" + UUID.randomUUID().toString().substring(0, 12);
    jdbcTemplate.update(insertTemplateSql, tenant, "MASKED", "masked", true);
    jdbcTemplate.update(insertTemplateSql, tenant, "PLAIN", "plain", false);
    Long maskedFile = jdbcTemplate.queryForObject(
        insertFileSql,
        Long.class,
        tenant,
        "masked-file",
        "masked-path",
        "{\"templateCode\":\"MASKED\"}");
    Long plainFile = jdbcTemplate.queryForObject(
        insertFileSql,
        Long.class,
        tenant,
        "plain-file",
        "plain-path",
        "{\"templateCode\":\"PLAIN\"}");
    jdbcTemplate.update(insertErrorSql, tenant, maskedFile, "MASKED_ERROR");
    jdbcTemplate.update(insertErrorSql, tenant, plainFile, "PLAIN_ERROR");
    jdbcTemplate.update(insertHistoricalErrorSql, tenant, maskedFile);
    BatchSecurityProperties security = new BatchSecurityProperties();
    security.setBypassMode(false);
    ConsoleFileQueryService queryService = new ConsoleFileQueryService(
        ignored -> tenant,
        fileQueryMappers,
        security,
        localizedErrorRenderer,
        mock(ConsoleOrchestratorPort.class),
        mock(BatchDateTimeSupport.class));
    FileErrorRecordQueryRequest request = new FileErrorRecordQueryRequest();
    request.setTenantId(tenant);

    var results = queryService.fileErrorRecords(request).items();

    assertThat(results).hasSize(3);
    var masked = results.stream()
        .filter(row -> "MASKED_ERROR".equals(row.errorCode()))
        .findFirst()
        .orElseThrow();
    var plain = results.stream()
        .filter(row -> row.fileId().equals(plainFile))
        .findFirst()
        .orElseThrow();
    assertThat(masked.rawRecord()).doesNotContain("1234567890", "Alice Smith", "Main Street");
    assertThat(masked.errorMessage()).doesNotContain("1234567890");
    assertThat(plain.rawRecord()).contains("1234567890", "Alice Smith", "Main Street");
    assertThat(plain.errorMessage()).contains("1234567890");
    var historical = results.stream()
        .filter(row -> "HISTORICAL_ERROR".equals(row.errorCode()))
        .findFirst()
        .orElseThrow();
    assertThat(historical.rawRecord()).doesNotContain("Historical Alice");
  }

  @Test
  @DisplayName("文件详情隐藏路径和嵌套元数据凭据，库中和内部存储投影保留原值")
  void shouldMaskFileDetailWithoutChangingStoredLocation() {
    final String insertFileSql = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
           source_type, file_status, metadata_json)
        values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED', ?::jsonb) returning id
        """;
    String tenant = "detail-mask-" + UUID.randomUUID().toString().substring(0, 12);
    String endpoint =
        "https://probe-user:probe-password@invalid.example/path?token=probe-token&view=ok";
    String metadata = JsonUtils.toJson(Map.of(
        "token",
        "probe-meta",
        "nested",
        Map.of("password", "probe-nested", "endpoint", endpoint),
        "urls",
        List.of(endpoint),
        "notes",
        "first line\nsecond line",
        "count",
        7));
    Long fileId = jdbcTemplate.queryForObject(
        insertFileSql, Long.class, tenant, "detail-file", endpoint, metadata);

    String response = client
        .get()
        .uri("/api/console/queries/files/{id}?tenantId={tenant}", fileId, tenant)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();

    assertThat(response)
        .isNotNull()
        .doesNotContain(
            "probe-user", "probe-password", "probe-token", "probe-meta", "probe-nested");
    JsonNode detail = JsonUtils.fromJson(response, JsonNode.class).path("data");
    assertThat(detail.path("metadata_json").isObject()).isTrue();
    assertThat(detail.path("metadata_json").path("count").asInt()).isEqualTo(7);
    assertThat(detail.path("metadata_json").path("notes").asText())
        .isEqualTo("first line\nsecond line");
    assertThat(detail.path("storage_path").asText()).contains("view=ok");
    assertThat(fileRecordMapper.selectFileRecordById(tenant, fileId).storagePath())
        .isEqualTo(endpoint);
    assertThat(fileRecordMapper.selectFileRecordById(tenant + "-other", fileId)).isNull();
  }

  @Test
  @DisplayName("文件派发列表和回执别名不回显目标及错误中的凭据")
  void shouldMaskDispatchAndReceiptQueriesWithoutChangingStoredTarget() {
    final String insertFileSql = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
           source_type, file_status)
        values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED') returning id
        """;
    final String insertDispatchSql = """
        insert into batch.file_dispatch_record
          (tenant_id, file_id, channel_code, dispatch_status, dispatch_target, error_message)
        values (?, ?, 'SECURITY_TEST', 'FAILED', ?, ?)
        """;
    final String insertLocalizedDispatchSql = """
        insert into batch.file_dispatch_record
          (tenant_id, file_id, channel_code, dispatch_status, dispatch_target, error_key, error_args)
        values (?, ?, 'SECURITY_TEST_2', 'FAILED', ?, 'error.common.conflict_detail', ?::jsonb)
        """;
    final String selectTargetSql =
        "select dispatch_target from batch.file_dispatch_record where tenant_id = ? and file_id = ? and channel_code = 'SECURITY_TEST'";
    String tenant = "dispatch-mask-" + UUID.randomUUID().toString().substring(0, 12);
    String target =
        "https://probe-user:probe-password@invalid.example/path?token=probe-token&view=ok";
    String error = "delivery failed token=probe-error";
    Long fileId = jdbcTemplate.queryForObject(insertFileSql, Long.class, tenant, "file", "fixture");
    jdbcTemplate.update(insertDispatchSql, tenant, fileId, target, error);
    jdbcTemplate.update(
        insertLocalizedDispatchSql, tenant, fileId, target, "[\"token=probe-arg\"]");

    for (String path :
        List.of("/api/console/queries/file-dispatches", "/api/console/queries/channel-receipts")) {
      String response = client
          .get()
          .uri(path + "?tenantId={tenant}&fileId={id}", tenant, fileId)
          .exchange()
          .expectStatus()
          .isOk()
          .expectBody(String.class)
          .returnResult()
          .getResponseBody();
      assertThat(response)
          .isNotNull()
          .contains("SECURITY_TEST", "SECURITY_TEST_2", "view=ok")
          .doesNotContain(
              "probe-user", "probe-password", "probe-token", "probe-error", "probe-arg");
    }
    assertThat(jdbcTemplate.queryForObject(selectTargetSql, String.class, tenant, fileId))
        .isEqualTo(target);
  }

  @ParameterizedTest
  @ValueSource(strings = {"file-pipeline-steps", "file-errors"})
  @DisplayName("文件步骤和错误查询不回显摘要 URL 或错误消息中的凭据")
  void shouldMaskPipelineEvidenceAndErrorMessages(String path) {
    final String insertFileSql = """
        insert into batch.file_record
          (tenant_id, file_category, file_name, file_format_type, storage_type, storage_path,
           source_type, file_status)
        values (?, 'OUTPUT', ?, 'BINARY', 'LOCAL', ?, 'SYSTEM', 'RECEIVED') returning id
        """;
    final String insertPipelineSql = """
        insert into batch.pipeline_instance
          (tenant_id, system_pipeline_code, system_pipeline_version, job_code, pipeline_type,
           file_id, run_status)
        values (?, 'DISPATCH', 1, ?, 'DISPATCH', ?, 'FAILED') returning id
        """;
    final String insertStepSql = """
        insert into batch.pipeline_step_run
          (pipeline_instance_id, step_code, stage_code, step_status, input_summary, output_summary,
           error_code, error_message)
        values (?, 'STEP_A', 'DELIVER', 'FAILED', ?::jsonb, ?::jsonb, 'STEP_FAILED', ?)
        """;
    final String insertLocalizedStepSql = """
        insert into batch.pipeline_step_run
          (pipeline_instance_id, step_code, stage_code, step_status, error_code, error_key, error_args)
        values (?, 'STEP_B', 'DELIVER', 'FAILED', 'STEP_FAILED', 'error.common.conflict_detail', ?::jsonb)
        """;
    final String insertErrorSql = """
        insert into batch.file_error_record
          (tenant_id, file_id, pipeline_instance_id, error_code, error_message, raw_record)
        values (?, ?, ?, 'FAIL_CODE', ?, '{"value":"safe_value"}'::jsonb)
        """;
    String tenant = "pipeline-mask-" + UUID.randomUUID().toString().substring(0, 12);
    String target =
        "https://probe-user:probe-password@invalid.example/path?token=probe-token&view=ok";
    String summary =
        JsonUtils.toJson(Map.of("endpoint", target, "nested", List.of(target), "count", 7));
    String error = "delivery failed token=probe-error";
    Long fileId = jdbcTemplate.queryForObject(insertFileSql, Long.class, tenant, "file", "fixture");
    Long pipelineId =
        jdbcTemplate.queryForObject(insertPipelineSql, Long.class, tenant, "JOB", fileId);
    jdbcTemplate.update(insertStepSql, pipelineId, summary, summary, error);
    jdbcTemplate.update(insertLocalizedStepSql, pipelineId, "[\"token=probe-arg\"]");
    jdbcTemplate.update(insertErrorSql, tenant, fileId, pipelineId, error);

    String response = client
        .get()
        .uri(
            "/api/console/queries/" + path
                + "?tenantId={tenant}&fileId={file}&pipelineInstanceId={pipeline}",
            tenant,
            fileId,
            pipelineId)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();

    assertThat(response)
        .isNotNull()
        .doesNotContain("probe-user", "probe-password", "probe-token", "probe-error", "probe-arg");
    if ("file-pipeline-steps".equals(path)) {
      assertThat(response).contains("STEP_A", "STEP_B", "view=ok");
    } else {
      assertThat(response).contains("FAIL_CODE", "safe_value");
    }
  }
}
