package io.github.pinpols.batch.common.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import org.junit.jupiter.api.Test;

class ExportFileNameResolverTest {

  @Test
  void resolvesEverySupportedPlaceholder() {
    String result = ExportFileNameResolver.resolve(ExportFileNameResolver.Input.builder()
        .namingRule("${bizType}_${bizDate}_${tenantId}_${batchNo}_${region}_${version}.csv")
        .fileFormatType(FileTemplateFormat.DELIMITED.code())
        .bizType("SETTLEMENT")
        .bizDate("2026-09-27")
        .tenantId("tenant-a")
        .batchNo("B001")
        .region("cn-east")
        .version("v3")
        .build());

    assertThat(result).isEqualTo("SETTLEMENT_2026-09-27_tenant-a_B001_cn-east_v3.csv");
  }

  @Test
  void fallsBackToRuntimeDefaultNameAndExtension() {
    assertThat(ExportFileNameResolver.resolve(ExportFileNameResolver.Input.builder()
            .fileFormatType(FileTemplateFormat.EXCEL.code())
            .bizType("CUSTOMER")
            .bizDate("2026-09-27")
            .tenantId("tenant-a")
            .build()))
        .isEqualTo("CUSTOMER_2026-09-27_batch.xlsx");
  }
}
