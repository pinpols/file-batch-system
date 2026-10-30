package io.github.pinpols.batch.worker.exports.stage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.plugin.WorkerPluginIds;
import io.github.pinpols.batch.testing.TestPostgresContainers;
import io.github.pinpols.batch.worker.core.config.WorkerCheckpointProperties;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineRuntimeKeys;
import io.github.pinpols.batch.worker.core.infrastructure.PipelineStageProgressRegistry;
import io.github.pinpols.batch.worker.core.infrastructure.checkpoint.ProcessingPositionStore;
import io.github.pinpols.batch.worker.exports.config.ExportWorkerConfiguration;
import io.github.pinpols.batch.worker.exports.config.SqlTemplateExportSecurityProperties;
import io.github.pinpols.batch.worker.exports.domain.ExportJobContext;
import io.github.pinpols.batch.worker.exports.domain.ExportPayload;
import io.github.pinpols.batch.worker.exports.domain.ExportStageResult;
import io.github.pinpols.batch.worker.exports.plugin.ExportDataPluginRegistry;
import io.github.pinpols.batch.worker.exports.plugin.SqlTemplateExportDataPlugin;
import io.github.pinpols.batch.worker.exports.stage.format.ExportFormatStrategyRegistry;
import io.github.pinpols.batch.worker.exports.stage.format.GenerateCursorCodec;
import io.github.pinpols.batch.worker.exports.stage.format.JsonExportFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("导出演练生成阶段的数据库副作用")
class GenerateDryRunSqlIntegrationTest {

  @Test
  @DisplayName("演练的 SQL 模板不得在计划阶段修改业务数据库序列")
  void shouldNotRunSqlTemplate_whenGeneratingDryRun() {
    try (PostgreSQLContainer postgres = TestPostgresContainers.platform()) {
      postgres.start();
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setURL(postgres.getJdbcUrl());
      dataSource.setUser(postgres.getUsername());
      dataSource.setPassword(postgres.getPassword());
      JdbcTemplate jdbc = new JdbcTemplate(dataSource);
      String createSequenceSql = "CREATE SEQUENCE export_probe_sequence";
      String createFunctionSql = """
          CREATE FUNCTION export_probe_immutable() RETURNS bigint LANGUAGE SQL IMMUTABLE
          AS 'SELECT nextval(''export_probe_sequence'')'
          """;
      String sequenceStateSql = "SELECT is_called FROM export_probe_sequence";
      jdbc.execute(createSequenceSql);
      jdbc.execute(createFunctionSql);

      ObjectMapper objectMapper = new ObjectMapper();
      ExportWorkerConfiguration config = new ExportWorkerConfiguration(
          "worker-test",
          "EXPORT",
          "tenant-test",
          5000L,
          "batch-export",
          "group-export",
          null,
          500_000L,
          new ExportWorkerConfiguration.FileProcessing(true, 100, 100, 50));
      SqlTemplateExportSecurityProperties security = new SqlTemplateExportSecurityProperties();
      security.setExplainCheckEnabled(true);
      var plugin = new SqlTemplateExportDataPlugin(dataSource, objectMapper, security, config);
      ExportDataPluginRegistry registry = mock(ExportDataPluginRegistry.class);
      when(registry.require(WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE)).thenReturn(plugin);
      WorkerCheckpointProperties checkpoints = new WorkerCheckpointProperties();
      checkpoints.setEnabled(false);
      var step = new GenerateStep(
          registry,
          new ExportFormatStrategyRegistry(List.of(new JsonExportFormat(objectMapper))),
          config,
          objectMapper,
          new GenerateRuntimeSupport(
              checkpoints,
              mock(ProcessingPositionStore.class),
              new GenerateCursorCodec(),
              new PipelineStageProgressRegistry()));
      ExportJobContext context = new ExportJobContext();
      context.setTenantId("tenant-test");
      context.setJobCode("job-test");
      context.setWorkerId("worker-test");
      context
          .getAttributes()
          .put(
              "exportPayload",
              new ExportPayload(
                  null, null, "template", "batch", null, null, null, null, null, null, Map.of()));
      context.getAttributes().put(PipelineRuntimeKeys.DRY_RUN, true);
      context.getAttributes().put(PipelineRuntimeKeys.EXPORT_FILE_FORMAT_TYPE, "JSON");
      context
          .getAttributes()
          .put(
              PipelineRuntimeKeys.TEMPLATE_CONFIG,
              Map.of(
                  "export_data_ref",
                  WorkerPluginIds.EXPORT_DATA_SQL_TEMPLATE,
                  "default_query_sql",
                  "SELECT export_probe_immutable() AS id"
                      + " WHERE :tenantId <> '' AND :batchNo <> ' '"));

      assertThat(jdbc.queryForObject(sequenceStateSql, Boolean.class)).isFalse();
      ExportStageResult result = step.execute(context);
      assertThat(jdbc.queryForObject(sequenceStateSql, Boolean.class)).isFalse();
      assertThat(result.success()).isFalse();
      assertThat(result.code()).isEqualTo("EXPORT_DRY_RUN_SQL_UNAVAILABLE");
      assertThat(result.errorKey()).isEqualTo("error.export.generate.dry_run_sql_unavailable");
    }
  }
}
