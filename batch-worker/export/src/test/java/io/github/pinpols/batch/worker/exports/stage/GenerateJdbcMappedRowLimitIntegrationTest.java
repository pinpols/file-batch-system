package io.github.pinpols.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineStageProgressRegistry;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.exports.config.ExportWorkerConfiguration;
import io.github.pinpols.batch.worker.exports.config.JdbcMappedExportSecurityProperties;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import io.github.pinpols.batch.worker.exports.plugin.ExportDataPluginRegistry;
import io.github.pinpols.batch.worker.exports.plugin.GenericJdbcMappedExportDataPlugin;
import io.github.pinpols.batch.worker.exports.stage.format.ExportFormatStrategyRegistry;
import io.github.pinpols.batch.worker.exports.stage.format.GenerateCursorCodec;
import io.github.pinpols.batch.worker.exports.stage.format.JsonExportFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("JDBC 映射导出行数上限")
class GenerateJdbcMappedRowLimitIntegrationTest {

  @ParameterizedTest
  @ValueSource(ints = {2, 3})
  @DisplayName("批次未投影总数时按实际明细数执行上限")
  void shouldEnforceConfiguredRowLimit_whenBatchHasNoTotalCount(int rowCount) throws Exception {
    try (PostgreSQLContainer postgres = TestPostgresContainers.platform()) {
      postgres.start();
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setURL(postgres.getJdbcUrl());
      dataSource.setUser(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String createSchemaSql = "CREATE SCHEMA biz";
      String createBatchSql = """
          CREATE TABLE biz.export_batch (
            id bigint primary key, tenant_id text not null, batch_no text not null
          )
          """;
      String createDetailSql = """
          CREATE TABLE biz.export_detail (
            id bigint primary key, batch_id bigint not null, content text not null
          )
          """;
      String insertBatchSql = "INSERT INTO biz.export_batch VALUES (1, 'tenant-test', 'B1')";
      String insertDetailSql = """
          INSERT INTO biz.export_detail
          SELECT n, 1, 'row-' || n FROM generate_series(1, ?) AS n
          """;
      jdbc.execute(createSchemaSql);
      jdbc.execute(createBatchSql);
      jdbc.execute(createDetailSql);
      jdbc.update(insertBatchSql);
      jdbc.update(insertDetailSql, rowCount);

      ObjectMapper mapper = new ObjectMapper();
      GenericJdbcMappedExportDataPlugin plugin = new GenericJdbcMappedExportDataPlugin(
          dataSource, mapper, new JdbcMappedExportSecurityProperties());
      ExportDataPluginRegistry registry = mock(ExportDataPluginRegistry.class);
      when(registry.require(WorkerPluginIds.EXPORT_DATA_JDBC_MAPPED)).thenReturn(plugin);
      ExportWorkerConfiguration config = new ExportWorkerConfiguration(
          "worker-test",
          "EXPORT",
          "tenant-test",
          5000L,
          "batch-export",
          "group-export",
          null,
          2L,
          new ExportWorkerConfiguration.FileProcessing(true, 100, 100, 50));
      WorkerCheckpointProperties checkpoints = new WorkerCheckpointProperties();
      checkpoints.setEnabled(false);
      GenerateStep step = new GenerateStep(
          registry,
          new ExportFormatStrategyRegistry(List.of(new JsonExportFormat(mapper))),
          config,
          mapper,
          new GenerateRuntimeSupport(
              checkpoints,
              mock(ProcessingPositionStore.class),
              new GenerateCursorCodec(),
              new PipelineStageProgressRegistry()));
      Map<String, Object> mapping = Map.of(
          "schema", "biz",
          "batchTable", "export_batch",
          "batchTenantColumn", "tenant_id",
          "batchNoColumn", "batch_no",
          "batchSelectColumns", List.of("id", "tenant_id", "batch_no"),
          "detailTable", "export_detail",
          "detailFkColumn", "batch_id",
          "detailOrderByColumn", "id",
          "detailSelectColumns", List.of("id", "batch_id", "content"));
      ExportJobContext context = new ExportJobContext();
      context.setTenantId("tenant-test");
      context.setJobCode("job-test");
      context.setWorkerId("worker-test");
      context
          .getAttributes()
          .put(
              "exportPayload",
              new ExportPayload(
                  null, null, "template", "B1", null, null, null, null, null, null, Map.of()));
      context.getAttributes().put(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE, "JSON");
      context
          .getAttributes()
          .put(
              PipelineRuntimeKeys.TEMPLATE_CONFIG,
              Map.of(
                  "export_data_ref",
                  WorkerPluginIds.EXPORT_DATA_JDBC_MAPPED,
                  "jdbc_mapped_export",
                  mapping));

      ExportStageResult result;
      RlsTenantContextHolder.set("tenant-test");
      try {
        result = step.execute(context);
      } finally {
        RlsTenantContextHolder.clear();
      }
      Object generatedPath = context.getAttributes().get(PipelineRuntimeKeys.GENERATED_FILE_PATH);
      try {
        if (rowCount == 2) {
          assertThat(result.success()).isTrue();
          assertThat(context.getAttributes()).containsEntry(PipelineRuntimeKeys.RECORD_COUNT, 2L);
        } else {
          assertThat(result.success()).isFalse();
          assertThat(result.code()).isEqualTo("EXPORT_EXCEEDS_MAX_ROWS");
          assertThat(generatedPath).isNull();
        }
      } finally {
        if (generatedPath != null) {
          Files.deleteIfExists(Path.of(generatedPath.toString()));
        }
      }
    }
  }
}
