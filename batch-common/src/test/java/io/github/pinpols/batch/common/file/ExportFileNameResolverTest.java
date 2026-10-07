package io.github.pinpols.batch.common.file;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("导出文件名解析:占位符全量替换与默认名回退")
class ExportFileNameResolverTest {

  @Test
  @DisplayName("命名规则给出全部占位符时逐一替换为业务取值")
  void shouldResolveAllPlaceholders_whenNamingRuleIsComplete() {
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
  @DisplayName("未配置命名规则时回退到业务类型与日期拼出的默认名与扩展名")
  void shouldUseDefaultNameAndExtension_whenNamingRuleIsMissing() {
    assertThat(ExportFileNameResolver.resolve(ExportFileNameResolver.Input.builder()
            .fileFormatType(FileTemplateFormat.EXCEL.code())
            .bizType("CUSTOMER")
            .bizDate("2026-09-27")
            .tenantId("tenant-a")
            .build()))
        .isEqualTo("CUSTOMER_2026-09-27_batch.xlsx");
  }
}
