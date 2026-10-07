package io.github.pinpols.batch.worker.imports.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.pinpols.batch.common.exception.WorkerConfigException;
import io.github.pinpols.batch.common.plugin.ImportLoadContext;
import io.github.pinpols.batch.worker.imports.jdbc.ImportLoadStrategy;
import io.github.pinpols.batch.worker.imports.jdbc.JdbcMappedImportSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** systemBindings 占位符解析 + 地区(region)默认回退/字典校验。 */
@DisplayName("通用 JDBC 映射导入加载插件单测:占位符解析,地区回退与冲突列排序语义")
class GenericJdbcMappedImportLoadPluginTest {

  private static ImportLoadContext ctx() {
    // 顺序对齐 ImportLoadContext record:tenantId/jobCode/traceId/workerId/sourceFileName/
    //                          batchNo/bizDate/bizType/region/templateCode/templateConfig
    return new ImportLoadContext(
        "t1",
        "TA_IMPORT_CUSTOMER",
        "trace-1",
        "worker-1",
        "cust.csv",
        "BATCH-1",
        "2026-06-06",
        "CUSTOMER",
        "GD",
        "TA_IMPORT_CUSTOMER_TPL",
        Map.of());
  }

  private static JdbcMappedImportSpec spec(String defaultRegion, List<String> allowedRegions) {
    return new JdbcMappedImportSpec(
        "biz",
        "customer_account",
        "tenant_id",
        List.of(new JdbcMappedImportSpec.ColumnMapping("customer_no", "customer_no")),
        List.of("tenant_id", "customer_no"),
        Map.of(),
        defaultRegion,
        allowedRegions,
        ImportLoadStrategy.BATCH_UPSERT,
        List.of(),
        null);
  }

  @Test
  @DisplayName("业务日期,业务类型与地区占位符都能解析出实际值")
  void shouldResolveBizDateBizTypeAndRegion() {
    ImportLoadContext c = ctx();
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizDate}", c))
        .isEqualTo("2026-06-06");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizType}", c))
        .isEqualTo("CUSTOMER");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${region}", c)).isEqualTo("GD");
  }

  @Test
  @DisplayName("租户,批次,作业与模板占位符按上下文原值解析")
  void shouldResolveExistingBindings() {
    ImportLoadContext c = ctx();
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${tenantId}", c))
        .isEqualTo("t1");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${batchNo}", c))
        .isEqualTo("BATCH-1");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${jobCode}", c))
        .isEqualTo("TA_IMPORT_CUSTOMER");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${templateCode}", c))
        .isEqualTo("TA_IMPORT_CUSTOMER_TPL");
  }

  @Test
  @DisplayName("多个占位符混排时按顺序整体替换")
  void shouldInterpolateMixedPattern() {
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding(
            "${region}/${bizType}-${bizDate}", ctx()))
        .isEqualTo("GD/CUSTOMER-2026-06-06");
  }

  @Test
  @DisplayName("上下文字段缺失时,占位符渲染为空串")
  void shouldRenderNullContextValueAsEmpty() {
    ImportLoadContext c =
        new ImportLoadContext("t1", "JOB", "tr", "w", "f", "B", null, null, null, "TPL", Map.of());
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizDate}", c))
        .isEmpty();
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${region}", c)).isEmpty();
  }

  @Test
  @DisplayName("触发带入的地区在允许字典内时保持不变")
  void applyRegion_keepsTriggerRegionWhenAllowed() {
    ImportLoadContext c =
        GenericJdbcMappedImportLoadPlugin.applyRegion(ctx(), spec(null, List.of("BJ", "SH", "GD")));
    assertThat(c.region()).isEqualTo("GD");
  }

  @Test
  @DisplayName("未提供地区时回退到模板默认地区")
  void applyRegion_fallsBackToTemplateDefaultWhenMissing() {
    ImportLoadContext noRegion = new ImportLoadContext(
        "t1", "JOB", "tr", "w", "f", "B", "2026-06-06", "T", null, "TPL", Map.of());
    ImportLoadContext c =
        GenericJdbcMappedImportLoadPlugin.applyRegion(noRegion, spec("SH", List.of("BJ", "SH")));
    assertThat(c.region()).isEqualTo("SH");
  }

  @Test
  @DisplayName("地区不在字典内时抛配置异常,并提示允许列表")
  void applyRegion_rejectsRegionNotInDictionary() {
    ImportLoadContext bad = new ImportLoadContext(
        "t1", "JOB", "tr", "w", "f", "B", "2026-06-06", "T", "XX", "TPL", Map.of());
    assertThatThrownBy(() ->
            GenericJdbcMappedImportLoadPlugin.applyRegion(bad, spec(null, List.of("BJ", "SH"))))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("allowedRegions");
  }

  @Test
  @DisplayName("地区字典为空时不做校验,原样保留地区")
  void applyRegion_noValidationWhenDictionaryEmpty() {
    ImportLoadContext c =
        GenericJdbcMappedImportLoadPlugin.applyRegion(ctx(), spec(null, List.of()));
    assertThat(c.region()).isEqualTo("GD");
  }

  @Test
  @DisplayName("按冲突键对批次排序,保证写入顺序稳定")
  void orderedRecordsForConflictColumns_sortsBatchByConflictKey() {
    JdbcMappedImportSpec spec = spec(null, List.of());
    List<Map<String, Object>> records = List.of(
        Map.of("customer_no", "C-003"),
        Map.of("customer_no", "A-001"),
        Map.of("customer_no", "B-002"));

    List<Map<String, Object>> ordered =
        GenericJdbcMappedImportLoadPlugin.orderedRecordsForConflictColumns(
            ctx(), spec, List.of("tenant_id", "customer_no"), records);

    assertThat(ordered)
        .extracting(row -> row.get("customer_no"))
        .containsExactly("A-001", "B-002", "C-003");
  }
}
