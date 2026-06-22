package com.example.batch.worker.imports.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.batch.common.exception.WorkerConfigException;
import com.example.batch.common.plugin.ImportLoadContext;
import com.example.batch.worker.imports.jdbc.ImportLoadStrategy;
import com.example.batch.worker.imports.jdbc.JdbcMappedImportSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** systemBindings 占位符解析 + 地区(region)默认回退/字典校验。 */
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
  void shouldResolveBizDateBizTypeAndRegion() {
    ImportLoadContext c = ctx();
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizDate}", c))
        .isEqualTo("2026-06-06");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizType}", c))
        .isEqualTo("CUSTOMER");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${region}", c)).isEqualTo("GD");
  }

  @Test
  void shouldResolveExistingBindings() {
    ImportLoadContext c = ctx();
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${tenantId}", c)).isEqualTo("t1");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${batchNo}", c))
        .isEqualTo("BATCH-1");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${jobCode}", c))
        .isEqualTo("TA_IMPORT_CUSTOMER");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${templateCode}", c))
        .isEqualTo("TA_IMPORT_CUSTOMER_TPL");
  }

  @Test
  void shouldInterpolateMixedPattern() {
    assertThat(
            GenericJdbcMappedImportLoadPlugin.resolveBinding(
                "${region}/${bizType}-${bizDate}", ctx()))
        .isEqualTo("GD/CUSTOMER-2026-06-06");
  }

  @Test
  void shouldRenderNullContextValueAsEmpty() {
    ImportLoadContext c =
        new ImportLoadContext("t1", "JOB", "tr", "w", "f", "B", null, null, null, "TPL", Map.of());
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizDate}", c)).isEmpty();
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${region}", c)).isEmpty();
  }

  @Test
  void applyRegion_keepsTriggerRegionWhenAllowed() {
    ImportLoadContext c =
        GenericJdbcMappedImportLoadPlugin.applyRegion(ctx(), spec(null, List.of("BJ", "SH", "GD")));
    assertThat(c.region()).isEqualTo("GD");
  }

  @Test
  void applyRegion_fallsBackToTemplateDefaultWhenMissing() {
    ImportLoadContext noRegion =
        new ImportLoadContext(
            "t1", "JOB", "tr", "w", "f", "B", "2026-06-06", "T", null, "TPL", Map.of());
    ImportLoadContext c =
        GenericJdbcMappedImportLoadPlugin.applyRegion(noRegion, spec("SH", List.of("BJ", "SH")));
    assertThat(c.region()).isEqualTo("SH");
  }

  @Test
  void applyRegion_rejectsRegionNotInDictionary() {
    ImportLoadContext bad =
        new ImportLoadContext(
            "t1", "JOB", "tr", "w", "f", "B", "2026-06-06", "T", "XX", "TPL", Map.of());
    assertThatThrownBy(
            () ->
                GenericJdbcMappedImportLoadPlugin.applyRegion(bad, spec(null, List.of("BJ", "SH"))))
        .isInstanceOf(WorkerConfigException.class)
        .hasMessageContaining("allowedRegions");
  }

  @Test
  void applyRegion_noValidationWhenDictionaryEmpty() {
    ImportLoadContext c =
        GenericJdbcMappedImportLoadPlugin.applyRegion(ctx(), spec(null, List.of()));
    assertThat(c.region()).isEqualTo("GD");
  }
}
