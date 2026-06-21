package com.example.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.BatchConsoleApiApplication;
import com.example.batch.console.application.config.ConsoleTenantConfigPackageExcelApplicationService;
import com.example.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import com.example.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse;
import com.example.batch.console.web.response.config.TenantConfigPackageExcelPreviewResponse.ErrorRowDto;
import com.example.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 预览出错行内联编辑:preview 返回 errorRows + patchRow 改单元格后重校验。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"batch.security.bypass-mode=true", "batch.console.ai.enabled=false"})
class ConsoleTenantConfigPackageExcelPatchIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ConsoleTenantConfigPackageExcelApplicationService service;
  @Autowired private TenantConfigPackageExcelImportStore importStore;

  @Test
  void shouldExposeErrorRowsThenFixViaPatch() {
    String tenantId = "excel-patch-ta";
    // channel 行:channel_name 留空 → 校验不过(其余字段齐全)
    Map<String, String> badChannel = channelRow(tenantId, "");
    String token = importStore.save(session(tenantId, List.of(badChannel)));

    // 1) preview 暴露出错行 + 整行单元格值
    TenantConfigPackageExcelPreviewResponse pv = service.preview(token);
    assertThat(pv.invalidRows()).isGreaterThanOrEqualTo(1);
    assertThat(pv.errorRows()).isNotEmpty();
    ErrorRowDto bad =
        pv.errorRows().stream()
            .filter(r -> ConfigPackageExcelValidator.CHANNEL_SHEET.equals(r.sheetName()))
            .findFirst()
            .orElseThrow();
    assertThat(bad.rowNo()).isEqualTo(2);
    assertThat(bad.values()).containsEntry("channel_code", "excel_patch_local");
    assertThat(bad.messages()).isNotEmpty();

    // 2) 内联编辑:补上 channel_name → 重校验该行通过
    TenantConfigPackageExcelPreviewResponse patched =
        service.patchRow(
            token,
            ConfigPackageExcelValidator.CHANNEL_SHEET,
            2,
            Map.of("channel_name", "Fixed Channel"));
    assertThat(patched.errorRows())
        .noneMatch(r -> ConfigPackageExcelValidator.CHANNEL_SHEET.equals(r.sheetName()));
    assertThat(patched.invalidRows()).isLessThan(pv.invalidRows());
  }

  @Test
  void shouldIgnoreUnknownColumnKeyOnPatch() {
    String tenantId = "excel-patch-tb";
    String token = importStore.save(session(tenantId, List.of(channelRow(tenantId, ""))));
    // 传一个该行不存在的列键 → 忽略,不凭空塞键、不抛错
    TenantConfigPackageExcelPreviewResponse patched =
        service.patchRow(
            token, ConfigPackageExcelValidator.CHANNEL_SHEET, 2, Map.of("not_a_real_column", "x"));
    assertThat(patched.errorRows())
        .anyMatch(r -> ConfigPackageExcelValidator.CHANNEL_SHEET.equals(r.sheetName()));
  }

  private static PackageExcelSession session(
      String tenantId, List<Map<String, String>> channelRows) {
    return new PackageExcelSession(
        "tenant-package.xlsx",
        tenantId,
        Instant.parse("2026-06-22T00:00:00Z"),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        channelRows,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  private static Map<String, String> channelRow(String tenantId, String channelName) {
    Map<String, String> row = new LinkedHashMap<>();
    row.put("tenant_id", tenantId);
    row.put("channel_code", "excel_patch_local");
    row.put("channel_name", channelName);
    row.put("channel_type", "LOCAL");
    row.put("target_endpoint", "/tmp/excel-patch");
    row.put("auth_type", "NONE");
    row.put("config_json", "{\"target_endpoint\":\"/tmp/excel-patch\"}");
    row.put("receipt_policy", "NONE");
    row.put("timeout_seconds", "30");
    row.put("enabled", "true");
    return row;
  }
}
