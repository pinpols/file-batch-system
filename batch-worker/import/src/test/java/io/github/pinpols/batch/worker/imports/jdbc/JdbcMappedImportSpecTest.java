package io.github.pinpols.batch.worker.imports.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.worker.imports.jdbc.JdbcMappedImportSpec.ColumnMapping;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

@DisplayName("JDBC 映射导入规格解析单测:列映射推断,冲突键补全与标识符校验语义")
class JdbcMappedImportSpecTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("解析顶层规格:库名,表名,租户列,列映射与加载策略都正确取出")
  void shouldParseTopLevelJdbcMappedImport() {
    Map<String, Object> template = Map.of(
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
  @DisplayName("缺少规格配置时抛参数非法")
  void shouldRejectMissingSpec() {
    assertThatThrownBy(() -> JdbcMappedImportSpec.parse(Map.of(), objectMapper))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("jdbc_mapped_import spec missing");
  }

  @Test
  @DisplayName("未提供列映射时,从字段映射推断出列对应关系")
  void shouldInferColumnMappingsFromFieldMappingsWhenOmitted() {
    Map<String, Object> template = Map.of(
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
  @DisplayName("显式列映射未写类型与格式时,从字段映射继承")
  void shouldInheritTypeAndFormat_whenExplicitMappingsIncomplete() {
    Map<String, Object> template = Map.of(
        "field_mappings",
        List.of(Map.of(
            "name", "txnDate",
            "targetColumn", "txn_date",
            "type", "DATE",
            "format", "yyyy-MM-dd")),
        "jdbc_mapped_import",
        Map.of(
            "schema", "biz",
            "table", "transaction",
            "tenantColumn", "tenant_id",
            "columnMappings", List.of(Map.of("from", "txnDate", "to", "txn_date"))));

    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(template, objectMapper);

    assertThat(spec.columnMappings())
        .extracting(
            ColumnMapping::from, ColumnMapping::to, ColumnMapping::type, ColumnMapping::format)
        .containsExactly(tuple("txnDate", "txn_date", "DATE", "yyyy-MM-dd"));
  }

  @Test
  @DisplayName("列映射为空数组时,仍按字段映射推断")
  void shouldInferWhenColumnMappingsIsEmptyJsonArray() {
    Map<String, Object> template = Map.of(
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
  @DisplayName("显式列映射按来源合并覆盖推断结果,仅差异项生效")
  void explicitMappingsOverrideInferredByFrom_onlyDiffsNeeded() {
    Map<String, Object> template = Map.of(
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
  @DisplayName("列映射与字段映射都缺失时抛参数非法")
  void shouldRejectWhenNeitherColumnMappingsNorFieldMappingsPresent() {
    Map<String, Object> template = Map.of(
        "jdbc_mapped_import",
        Map.of("schema", "biz", "table", "customer_account", "tenantColumn", "tenant_id"));

    assertThatThrownBy(() -> JdbcMappedImportSpec.parse(template, objectMapper))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("could not be inferred from field_mappings");
  }

  @Test
  @DisplayName("同一来源列映射到多个目标列时校验失败")
  void shouldRejectFanOutOneSourceToMultipleColumns() {
    JdbcMappedImportSpec spec = mappingSpec(List.of(
        new JdbcMappedImportSpec.ColumnMapping("fieldA", "col_x"),
        new JdbcMappedImportSpec.ColumnMapping("fieldA", "col_y")));

    assertThatThrownBy(() -> spec.validateIdentifiers(List.of("biz")))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("fan-out is not supported");
  }

  @Test
  @DisplayName("多个来源列映射到同一目标列时校验失败")
  void shouldRejectCollisionMultipleSourcesToOneColumn() {
    JdbcMappedImportSpec spec = mappingSpec(List.of(
        new JdbcMappedImportSpec.ColumnMapping("fieldA", "col_x"),
        new JdbcMappedImportSpec.ColumnMapping("fieldB", "col_x")));

    assertThatThrownBy(() -> spec.validateIdentifiers(List.of("biz")))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("single source");
  }

  @Test
  @DisplayName("列名归一化覆盖驼峰,全大写与下划线写法")
  void shouldNormalizeColumnNames_whenCamelOrUpperGiven() {
    assertThat(JdbcMappedImportSpec.normalizeColumn("customerNo")).isEqualTo("customer_no");
    assertThat(JdbcMappedImportSpec.normalizeColumn("CUSTOMER_NO")).isEqualTo("customer_no");
    assertThat(JdbcMappedImportSpec.normalizeColumn("customer_no")).isEqualTo("customer_no");
    assertThat(JdbcMappedImportSpec.normalizeColumn("customerID")).isEqualTo("customer_id");
    assertThat(JdbcMappedImportSpec.normalizeColumn("customerHTTPUrl"))
        .isEqualTo("customer_http_url");
  }

  @Test
  @DisplayName("冲突键未含租户列时,自动前置补上租户列")
  void shouldPrependTenantColumn_whenConflictColumnsMissingTenant() {
    Map<String, Object> template = Map.of(
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
  @DisplayName("冲突键已含租户列时,保持原样不重复补")
  void shouldKeepConflictColumns_whenTenantAlreadyPresent() {
    Map<String, Object> template = Map.of(
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
  @DisplayName("未配置冲突键时,结果保持为空")
  void shouldKeepConflictColumnsEmpty_whenNoneConfigured() {
    Map<String, Object> template = Map.of(
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
  @DisplayName("标准审计字段绑定展开,显式配置可覆盖默认值")
  void shouldExpandStandardAuditBindings_whenExplicitOverrideGiven() {
    Map<String, Object> template = Map.of(
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
  @DisplayName("查询参数模板以数据库 json 对象给出时,规格同样解析正确")
  void shouldParseJdbcMappedImportWhenQueryParamSchemaIsPgJsonObject() throws Exception {
    Map<String, Object> qps = Map.of(
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
  @DisplayName("分区替换策略被解析,并带上替换分区列")
  void shouldParsePartitionReplaceCopyStrategy() {
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(
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
  @DisplayName("分区阶段交换策略被解析,含中间分区表名")
  void shouldParsePartitionStageSwapCopyStrategy() {
    JdbcMappedImportSpec spec = JdbcMappedImportSpec.parse(
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
  @DisplayName("开启严格幂等时,分区替换策略允许不配冲突键")
  void shouldAllowPartitionReplaceCopy_whenStrictIdempotencyEnabled() {
    JdbcMappedImportSpec spec =
        partitionReplaceSpec(List.of("tenant_id", "biz_date"), Map.of("biz_date", "${bizDate}"));

    spec.validateIdentifiers(List.of("biz"), true);
  }

  @Test
  @DisplayName("分区替换策略未声明替换分区列时,校验失败")
  void shouldRejectPartitionReplaceCopy_whenReplacePartitionColumnsMissing() {
    JdbcMappedImportSpec spec = partitionReplaceSpec(List.of(), Map.of("biz_date", "${bizDate}"));

    assertThatThrownBy(() -> spec.validateIdentifiers(List.of("biz"), true))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("replacePartitionColumns");
  }

  @Test
  @DisplayName("替换分区列无法解析到映射时,读取行之前即校验失败")
  void shouldReject_whenReplacePartitionColumnsUnresolvable() {
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
