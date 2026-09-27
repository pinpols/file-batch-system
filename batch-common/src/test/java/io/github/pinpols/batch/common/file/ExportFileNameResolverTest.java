package io.github.pinpols.batch.common.file;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExportFileNameResolverTest {

  @Test
  void resolvesEverySupportedPlaceholder() {
    String result = ExportFileNameResolver.resolve(
        "${bizType}_${bizDate}_${tenantId}_${batchNo}_${region}_${version}.csv",
        "DELIMITED",
        "SETTLEMENT",
        "2026-09-27",
        "tenant-a",
        "B001",
        "cn-east",
        "v3");

    assertThat(result).isEqualTo("SETTLEMENT_2026-09-27_tenant-a_B001_cn-east_v3.csv");
  }

  @Test
  void fallsBackToRuntimeDefaultNameAndExtension() {
    assertThat(ExportFileNameResolver.resolve(
            null, "EXCEL", "CUSTOMER", "2026-09-27", "tenant-a", null, null, null))
        .isEqualTo("CUSTOMER_2026-09-27_batch.xlsx");
  }
}
