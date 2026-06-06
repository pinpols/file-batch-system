package com.example.batch.worker.imports.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.common.plugin.ImportLoadContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** systemBindings 占位符解析(import 把系统上下文透传到目标业务表列)。 */
class GenericJdbcMappedImportLoadPluginTest {

  private static ImportLoadContext ctx() {
    // 顺序对齐 ImportLoadContext record:tenantId/jobCode/traceId/workerId/sourceFileName/
    //                                   batchNo/bizDate/bizType/templateCode/templateConfig
    return new ImportLoadContext(
        "t1",
        "TA_IMPORT_CUSTOMER",
        "trace-1",
        "worker-1",
        "cust.csv",
        "BATCH-1",
        "2026-06-06",
        "CUSTOMER",
        "TA_IMPORT_CUSTOMER_TPL",
        Map.of());
  }

  @Test
  void shouldResolveBizDateAndBizType() {
    ImportLoadContext c = ctx();
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizDate}", c))
        .isEqualTo("2026-06-06");
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizType}", c))
        .isEqualTo("CUSTOMER");
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
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizType}-${bizDate}", ctx()))
        .isEqualTo("CUSTOMER-2026-06-06");
  }

  @Test
  void shouldRenderNullContextValueAsEmpty() {
    ImportLoadContext c =
        new ImportLoadContext("t1", "JOB", "tr", "w", "f", "B", null, null, "TPL", Map.of());
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizDate}", c)).isEmpty();
    assertThat(GenericJdbcMappedImportLoadPlugin.resolveBinding("${bizType}", c)).isEmpty();
  }
}
