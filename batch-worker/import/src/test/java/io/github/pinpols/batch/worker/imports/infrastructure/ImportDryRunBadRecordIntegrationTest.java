package io.github.pinpols.batch.worker.imports.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.pinpols.batch.common.config.BatchSecurityProperties;
import io.github.pinpols.batch.common.config.S3StorageProperties;
import io.github.pinpols.batch.common.storage.FilesystemObjectStore;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileAuditRepository;
import io.github.pinpols.batch.worker.core.infrastructure.PlatformFileRecordRepository;
import io.github.pinpols.batch.worker.core.mapper.PlatformFileRuntimeMapper;
import io.github.pinpols.batch.worker.imports.config.ImportSkipProperties;
import io.github.pinpols.batch.worker.imports.domain.ImportJobContext;
import io.github.pinpols.batch.worker.imports.domain.ImportStage;
import io.github.pinpols.batch.worker.imports.infrastructure.ImportRecordGovernanceService.BadRecordCommand;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("导入演练坏记录真实存储隔离")
class ImportDryRunBadRecordIntegrationTest {
  @TempDir
  Path storageRoot;

  @Test
  @DisplayName("演练保留计数但不写错误记录或对象，正式导入仍写入")
  void shouldIsolateDryRunBadRecordsFromDatabaseAndObjectStore() throws Exception {
    try (PostgreSQLContainer postgres = TestPostgresContainers.platform()) {
      postgres.start();
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setURL(postgres.getJdbcUrl());
      dataSource.setUser(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String schemaSql = """
          CREATE SCHEMA batch;
          CREATE TABLE batch.file_record (id bigint primary key, tenant_id text,
              metadata_json jsonb not null default '{}', updated_at timestamptz default now());
          CREATE TABLE batch.pipeline_instance (id bigint primary key, tenant_id text);
          CREATE TABLE batch.pipeline_step_run (id bigint primary key, pipeline_instance_id bigint);
          CREATE TABLE batch.file_error_record (id bigserial primary key, tenant_id text, file_id bigint,
              pipeline_instance_id bigint, pipeline_step_run_id bigint, record_no bigint,
              error_code text, error_message text, error_key text, error_args jsonb, error_stage text,
              is_skipped boolean, skip_action text, raw_record jsonb, source_row_num bigint,
              source_column text, created_at timestamptz);
          CREATE TABLE batch.file_audit_log (tenant_id text, file_id bigint, operation_type text,
              operation_result text, operator_type text, operator_id text, trace_id text,
              evidence_ref text, detail_summary jsonb, created_at timestamptz);
          CREATE UNIQUE INDEX audit_dispatch ON batch.file_audit_log
              (tenant_id, file_id, evidence_ref, (detail_summary ->> 'channelCode'))
              WHERE operation_type = 'DISPATCH_COMPLETE' AND evidence_ref IS NOT NULL
                  AND detail_summary ->> 'channelCode' IS NOT NULL;
          INSERT INTO batch.file_record (id, tenant_id) VALUES (101, 'tenant-a'), (102, 'tenant-a');
          """;
      jdbc.execute(schemaSql);
      Configuration configuration = new Configuration(
          new Environment("dry-run-bad-record", new JdbcTransactionFactory(), dataSource));
      String resource = "mapper/PlatformFileRuntimeMapper.xml";
      try (InputStream input =
          Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
        assertThat(input).isNotNull();
        new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments())
            .parse();
      }
      try (SqlSession session =
          new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
        PlatformFileRuntimeMapper mapper = session.getMapper(PlatformFileRuntimeMapper.class);
        var store = new FilesystemObjectStore(storageRoot.toString(), "", "test-secret");
        S3StorageProperties storageProperties = new S3StorageProperties();
        storageProperties.setBucket("test-bucket");
        var service = new ImportRecordGovernanceService(
            new ImportSkipProperties(true, "ABSOLUTE", 5, 0.0, "", "CONTINUE", "BOTH", 7),
            new PlatformFileRecordRepository(mapper),
            new PlatformFileAuditRepository(mapper),
            new ImportErrorOutputStorage(storageProperties, store),
            new BatchSecurityProperties());
        String countErrorsSql = "SELECT count(*) FROM batch.file_error_record WHERE file_id = ?";
        String countAuditSql = "SELECT count(*) FROM batch.file_audit_log WHERE file_id = ?";
        String metadataSql = "SELECT metadata_json::text FROM batch.file_record WHERE id = ?";
        ImportJobContext dryRun = context(101L, true);
        recordAndFinalize(service, dryRun);

        assertThat(dryRun.getAttributes().get(PipelineRuntimeKeys.IMPORT_SKIPPED_COUNT))
            .isEqualTo(1L);
        assertThat(jdbc.queryForObject(countErrorsSql, Integer.class, 101L)).isZero();
        assertThat(jdbc.queryForObject(countAuditSql, Integer.class, 101L)).isZero();
        assertThat(jdbc.queryForObject(metadataSql, String.class, 101L)).isEqualTo("{}");
        assertThat(store.exists("test-bucket", "errors/tenant-a/101/101.error.jsonl"))
            .isFalse();

        ImportJobContext formal = context(102L, false);
        recordAndFinalize(service, formal);
        assertThat(jdbc.queryForObject(countErrorsSql, Integer.class, 102L)).isEqualTo(1);
        assertThat(jdbc.queryForObject(countAuditSql, Integer.class, 102L)).isEqualTo(1);
        assertThat(jdbc.queryForObject(metadataSql, String.class, 102L)).contains("badRecordCount");
        assertThat(store.exists("test-bucket", "errors/tenant-a/102/102.error.jsonl"))
            .isTrue();
        try (InputStream content =
            store.get("test-bucket", "errors/tenant-a/102/102.error.jsonl")) {
          String output = new String(content.readAllBytes(), StandardCharsets.UTF_8);
          assertThat(output).contains("invalid record", "bad");
        }

        formal
            .getAttributes()
            .put(
                PipelineRuntimeKeys.TEMPLATE_CONFIG,
                Map.of("error_line_masking_enabled", true, "masking_rule_set", "STRICT"));
        service.recordFailedRecord(
            formal,
            BadRecordCommand.builder()
                .stage(ImportStage.VALIDATE)
                .recordNo(2L)
                .errorCode("MASKED_ERROR")
                .errorMessage("invalid name=Alice")
                .rawRecord(Map.of("name", "Alice", "count", 12))
                .build());
        String selectMaskedRecordSql = """
            select jsonb_typeof(raw_record) as kind, raw_record::text as content
            from batch.file_error_record where tenant_id = ? and file_id = ? and record_no = ?
            """;
        var masked = jdbc.queryForMap(selectMaskedRecordSql, "tenant-a", 102L, 2L);
        service.finalizeErrorOutput(formal);
        try (InputStream content =
            store.get("test-bucket", "errors/tenant-a/102/102.error.jsonl")) {
          String output = new String(content.readAllBytes(), StandardCharsets.UTF_8);
          assertThat(output).doesNotContain("Alice").contains("MASKED_ERROR");
          var outputRow = JsonUtils.fromJson(output.lines().toList().getLast(), JsonNode.class);
          assertThat(outputRow.path("rawRecord").isObject()).isTrue();
          assertThat(outputRow.path("rawRecord").path("name").asText()).isEqualTo("***");
        }
        assertThat(masked.get("kind")).isEqualTo("object");
        assertThat(String.valueOf(masked.get("content")))
            .doesNotContain("Alice")
            .contains("count");
      }
    }
  }

  private static ImportJobContext context(long fileId, boolean dryRun) {
    ImportJobContext context = new ImportJobContext();
    context.setTenantId("tenant-a");
    context.setWorkerId("worker-test");
    context.getAttributes().put(PipelineRuntimeKeys.FILE_ID, fileId);
    context.getAttributes().put(PipelineRuntimeKeys.DRY_RUN, dryRun);
    return context;
  }

  private static void recordAndFinalize(
      ImportRecordGovernanceService service, ImportJobContext context) {
    service.recordSkippedRecord(
        context,
        BadRecordCommand.builder()
            .stage(ImportStage.PARSE)
            .recordNo(1L)
            .errorCode("TEST_ERROR")
            .errorMessage("invalid record")
            .rawRecord("bad")
            .build());
    service.finalizeErrorOutput(context);
  }
}
