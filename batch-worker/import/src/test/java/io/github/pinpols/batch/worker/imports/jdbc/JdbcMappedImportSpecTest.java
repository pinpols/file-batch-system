package io.github.pinpols.batch.worker.imports.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.worker.imports.jdbc.JdbcMappedImportSpec.ColumnMapping;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

class JdbcMappedImportSpecTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldParseTopLevelJdbcMappedImport() {
    Map<String, Object> template =
        Map.of(
            "jdbc_mapped_import",
            Map.of(
                "schema",
                "biz",
                "table",
                "imp_orders",
                "tenantColumn",
                "tenant_id",
                "columnMappings",
                List.of(Map.of("from", "col_a", "to", "col_a")),
                "conflictColumns",
                List.of("id")));
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);
    assertThat(spec.schema()).isEqualTo("biz");
    assertThat(spec.table()).isEqualTo("imp_orders");
    assertThat(spec.tenantColumn()).isEqualTo("tenant_id");
    assertThat(spec.columnMappings()).hasSize(1);
    // B2: tenant 列(tenant_id)缺失时自动前置到 conflictColumns
    assertThat(spec.conflictColumns()).containsExactly("tenant_id", "id");
    assertThat(spec.loadStrategy()).isEqualTo(ImportLoadStrategy.BATCH_UPSERT);
  }

  @Test
  void shouldRejectMissingSpec() {
    assertThatThrownBy(() -> JdbcMappedImportSpec.parse(Map.of(), objectMapper))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("jdbc_mapped_import spec missing");
  }

  @Test
  void shouldInferColumnMappingsFromFieldMappingsWhenOmitted() {
    Map<String, Object> template =
        Map.of(
            "field_mappings",
            List.of(
                Map.of("name", "customerNo", "targetColumn", "customer_no"),
                // 无 targetColumn → 归一化 customerName → customer_name
                Map.of("name", "customerName"),
                // persist:false → 只校验不入库,不进推断
                Map.of("name", "creditLimit", "persist", false)),
            "jdbc_mapped_import",
            Map.of(
                "schema", "biz",
                "table", "customer_account",
                "tenantColumn", "tenant_id",
                "conflictColumns", List.of("tenant_id", "customer_no")));

    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);

    assertThat(spec.columnMappings())
        .extracting(ColumnMapping::from, ColumnMapping::to)
        .containsExactly(
            tuple("customerNo", "customer_no"), tuple("customerName", "customer_name"));
  }

  @Test
  void shouldInferWhenColumnMappingsIsEmptyJsonArray() {
    Map<String, Object> template =
        Map.of(
            "field_mappings",
            List.of(Map.of("name", "customerNo", "targetColumn", "customer_no")),
            "jdbc_mapped_import",
            Map.of(
                "schema", "biz",
                "table", "customer_account",
                "tenantColumn", "tenant_id",
                "columnMappings", List.of()));

    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);

    assertThat(spec.columnMappings()).hasSize(1);
    assertThat(spec.columnMappings().get(0).to()).isEqualTo("customer_no");
  }

  @Test
  void explicitMappingsOverrideInferredByFrom_onlyDiffsNeeded() {
    Map<String, Object> template =
        Map.of(
            "field_mappings",
            List.of(Map.of("name", "email"), Map.of("name", "phone")),
            "jdbc_mapped_import",
            Map.of(
                "schema", "biz",
                "table", "customer_account",
                "tenantColumn", "tenant_id",
                // 只需写名字对不上的差异项:phone → mobile_no
                "columnMappings", List.of(Map.of("from", "phone", "to", "mobile_no"))));

    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);

    assertThat(spec.columnMappings())
        .extracting(ColumnMapping::from, ColumnMapping::to)
        .containsExactly(tuple("email", "email"), tuple("phone", "mobile_no"));
  }

  @Test
  void shouldRejectWhenNeitherColumnMappingsNorFieldMappingsPresent() {
    Map<String, Object> template =
        Map.of(
            "jdbc_mapped_import",
            Map.of("schema", "biz", "table", "customer_account", "tenantColumn", "tenant_id"));

    assertThatThrownBy(() -> JdbcMappedImportSpec.parse(template, objectMapper))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("could not be inferred from field_mappings");
  }

  @Test
  void shouldRejectFanOutOneSourceToMultipleColumns() {
    JdbcMappedImportSpec spec =
        mappingSpec(
            List.of(
                new JdbcMappedImportSpec.ColumnMapping("fieldA", "col_x"),
                new JdbcMappedImportSpec.ColumnMapping("fieldA", "col_y")));

    assertThatThrownBy(() -> spec.validateIdentifiers(List.of("biz")))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("fan-out is not supported");
  }

  @Test
  void shouldRejectCollisionMultipleSourcesToOneColumn() {
    JdbcMappedImportSpec spec =
        mappingSpec(
            List.of(
                new JdbcMappedImportSpec.ColumnMapping("fieldA", "col_x"),
                new JdbcMappedImportSpec.ColumnMapping("fieldB", "col_x")));

    assertThatThrownBy(() -> spec.validateIdentifiers(List.of("biz")))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("single source");
  }

  @Test
  void normalizeColumnHandlesCamelUnderscoreAndCase() {
    assertThat(JdbcMappedImportSpec.normalizeColumn("customerNo")).isEqualTo("customer_no");
    assertThat(JdbcMappedImportSpec.normalizeColumn("CUSTOMER_NO")).isEqualTo("customer_no");
    assertThat(JdbcMappedImportSpec.normalizeColumn("customer_no")).isEqualTo("customer_no");
    assertThat(JdbcMappedImportSpec.normalizeColumn("customerID")).isEqualTo("customer_id");
    assertThat(JdbcMappedImportSpec.normalizeColumn("customerHTTPUrl"))
        .isEqualTo("customer_http_url");
  }

  @Test
  void conflictColumnsAutoPrependsTenantWhenMissing() {
    Map<String, Object> template =
        Map.of(
            "jdbc_mapped_import",
            Map.of(
                "schema", "biz",
                "table", "customer_account",
                "tenantColumn", "tenant_id",
                "columnMappings", List.of(Map.of("from", "customerNo", "to", "customer_no")),
                "conflictColumns", List.of("customer_no")));

    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);

    assertThat(spec.conflictColumns()).containsExactly("tenant_id", "customer_no");
  }

  @Test
  void conflictColumnsKeptWhenTenantAlreadyPresent() {
    Map<String, Object> template =
        Map.of(
            "jdbc_mapped_import",
            Map.of(
                "schema", "biz",
                "table", "customer_account",
                "tenantColumn", "tenant_id",
                "columnMappings", List.of(Map.of("from", "customerNo", "to", "customer_no")),
                "conflictColumns", List.of("tenant_id", "customer_no")));

    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);

    assertThat(spec.conflictColumns()).containsExactly("tenant_id", "customer_no");
  }

  @Test
  void emptyConflictColumnsStayEmpty() {
    Map<String, Object> template =
        Map.of(
            "jdbc_mapped_import",
            Map.of(
                "schema", "biz",
                "table", "customer_account",
                "tenantColumn", "tenant_id",
                "columnMappings", List.of(Map.of("from", "customerNo", "to", "customer_no"))));

    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);

    assertThat(spec.conflictColumns()).isEmpty();
  }

  @Test
  void standardAuditBindingsExpandWithExplicitOverride() {
    Map<String, Object> template =
        Map.of(
            "jdbc_mapped_import",
            Map.of(
                "schema",
                "biz",
                "table",
                "customer_account",
                "tenantColumn",
                "tenant_id",
                "columnMappings",
                List.of(Map.of("from", "customerNo", "to", "customer_no")),
                "standardAuditBindings",
                true,
                // 用户显式 created_by 覆盖标准默认
                "systemBindings",
                Map.of("created_by", "${customWorker}")));

    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);

    assertThat(spec.systemBindings())
        .containsEntry("source_batch_no", "${batchNo}")
        .containsEntry("source_trace_id", "${traceId}")
        .containsEntry("source_file_name", "${sourceFileName}")
        .containsEntry("updated_by", "${workerId}")
        .containsEntry("created_by", "${customWorker}");
  }

  private static JdbcMappedImportSpec mappingSpec(
      List<JdbcMappedImportSpec.ColumnMapping> mappings) {
    return new JdbcMappedImportSpec(
        "biz",
        "customer_account",
        "tenant_id",
        mappings,
        List.of(),
        Map.of(),
        null,
        List.of(),
        ImportLoadStrategy.BATCH_UPSERT,
        List.of(),
        null);
  }

  @Test
  void shouldParseJdbcMappedImportWhenQueryParamSchemaIsPgJsonObject() throws Exception {
    Map<String, Object> qps =
        Map.of(
            "jdbcMappedImport",
            Map.of(
                "schema",
                "biz",
                "table",
                "customer_account",
                "tenantColumn",
                "tenant_id",
                "columnMappings",
                List.of(Map.of("from", "customerNo", "to", "customer_no")),
                "conflictColumns",
                List.of("tenant_id", "customer_no")));
    PGobject pg = new PGobject();
    pg.setType("jsonb");
    pg.setValue(objectMapper.writeValueAsString(qps));
    Map<String, Object> template = Map.of("query_param_schema", pg);
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);
    assertThat(spec.table()).isEqualTo("customer_account");
    assertThat(spec.columnMappings()).hasSize(1);
  }

  @Test
  void shouldParsePartitionReplaceCopyStrategy() {
    JdbcMappedImportSpec spec =
        JdbcMappedImportSpec.parse(
            Map.of(
                "jdbc_mapped_import",
                Map.of(
                    "schema",
                    "biz",
                    "table",
                    "customer_account",
                    "tenantColumn",
                    "tenant_id",
                    "columnMappings",
                    List.of(Map.of("from", "customerNo", "to", "customer_no")),
                    "systemBindings",
                    Map.of("biz_date", "${bizDate}"),
                    "loadStrategy",
                    "partition-replace-copy",
                    "replacePartitionColumns",
                    List.of("tenant_id", "biz_date"))),
            objectMapper);

    assertThat(spec.loadStrategy()).isEqualTo(ImportLoadStrategy.PARTITION_REPLACE_COPY);
    assertThat(spec.replacePartitionColumns()).containsExactly("tenant_id", "biz_date");
  }

  @Test
  void shouldParsePartitionStageSwapCopyStrategy() {
    JdbcMappedImportSpec spec =
        JdbcMappedImportSpec.parse(
            Map.of(
                "jdbc_mapped_import",
                Map.of(
                    "schema",
                    "biz",
                    "table",
                    "customer_account",
                    "tenantColumn",
                    "tenant_id",
                    "columnMappings",
                    List.of(Map.of("from", "customerNo", "to", "customer_no")),
                    "systemBindings",
                    Map.of("biz_date", "${bizDate}"),
                    "loadStrategy",
                    "PARTITION_STAGE_SWAP_COPY",
                    "replacePartitionColumns",
                    List.of("tenant_id", "biz_date"),
                    "stageSwap",
                    Map.of(
                        "partitionTable",
                        "customer_account_20260607",
                        "attachClause",
                        "FOR VALUES FROM ('2026-06-07') TO ('2026-06-08')"))),
            objectMapper);

    assertThat(spec.loadStrategy()).isEqualTo(ImportLoadStrategy.PARTITION_STAGE_SWAP_COPY);
    assertThat(spec.stageSwap().partitionTable()).isEqualTo("customer_account_20260607");
  }

  @Test
  void strictIdempotencyAllowsPartitionReplaceCopyWithoutConflictColumns() {
    JdbcMappedImportSpec spec =
        partitionReplaceSpec(List.of("tenant_id", "biz_date"), Map.of("biz_date", "${bizDate}"));

    spec.validateIdentifiers(List.of("biz"), true);
  }

  @Test
  void partitionReplaceCopyRequiresReplacePartitionColumns() {
    JdbcMappedImportSpec spec = partitionReplaceSpec(List.of(), Map.of("biz_date", "${bizDate}"));

    assertThatThrownBy(() -> spec.validateIdentifiers(List.of("biz"), true))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("replacePartitionColumns");
  }

  @Test
  void partitionReplaceColumnsMustBeResolvableBeforeReadingRows() {
    JdbcMappedImportSpec spec = partitionReplaceSpec(List.of("customer_no"), Map.of());

    assertThatThrownBy(() -> spec.validateIdentifiers(List.of("biz"), false))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("resolvable before reading rows");
  }

  private static JdbcMappedImportSpec partitionReplaceSpec(
      List<String> replaceColumns, Map<String, String> systemBindings) {
    return new JdbcMappedImportSpec(
        "biz",
        "customer_account",
        "tenant_id",
        List.of(new JdbcMappedImportSpec.ColumnMapping("customerNo", "customer_no")),
        List.of(),
        systemBindings,
        null,
        List.of(),
        ImportLoadStrategy.PARTITION_REPLACE_COPY,
        replaceColumns,
        null);
  }
}
