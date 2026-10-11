package io.github.pinpols.batch.console.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.service.BatchObjectCryptoService;
import io.github.pinpols.batch.console.BatchConsoleApiApplication;
import io.github.pinpols.batch.console.application.config.TenantConfigPackageExcelService;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelPreviewResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigPackageExcelPreviewResponse.ErrorRowDto;
import io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelValidator;
import io.github.pinpols.batch.console.support.excel.RedisTenantConfigExcelStore;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore;
import io.github.pinpols.batch.console.support.excel.TenantConfigPackageExcelImportStore.PackageExcelSession;
import io.github.pinpols.batch.testing.AbstractIntegrationTest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestConstructor;

/** 预览出错行内联编辑:preview 返回 errorRows + patchRow 改单元格后重校验。 */
@SpringBootTest(
    classes = BatchConsoleApiApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@DisplayName("租户配置包 Excel 预览与行内修正: 出错行透出,单元格修订后重校验通过")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ConsoleTenantConfigPackageExcelPatchIntegrationTest extends AbstractIntegrationTest {

  private final TenantConfigPackageExcelService service;

  private final TenantConfigPackageExcelImportStore importStore;

  private final StringRedisTemplate redisTemplate;

  private final ObjectMapper objectMapper;

  private final BatchObjectCryptoService cryptoService;

  ConsoleTenantConfigPackageExcelPatchIntegrationTest(
      TenantConfigPackageExcelService service,
      TenantConfigPackageExcelImportStore importStore,
      StringRedisTemplate redisTemplate,
      ObjectMapper objectMapper,
      BatchObjectCryptoService cryptoService) {
    this.service = service;
    this.importStore = importStore;
    this.redisTemplate = redisTemplate;
    this.objectMapper = objectMapper;
    this.cryptoService = cryptoService;
  }

  @Test
  @DisplayName("预览暴露校验失败行及其单元格值,补齐必填列后该行通过重校验且失败行数下降")
  void shouldExposeErrorRowsThenFixViaPatch() {
    String tenantId = "excel-patch-ta";
    // channel 行:channel_name 留空 → 校验不过(其余字段齐全)
    Map<String, String> badChannel = channelRow(tenantId, "");
    String token = importStore.save(session(tenantId, List.of(badChannel)));

    // 1) preview 暴露出错行 + 整行单元格值
    TenantConfigPackageExcelPreviewResponse pv = service.preview(token);
    assertThat(pv.invalidRows()).isGreaterThanOrEqualTo(1);
    assertThat(pv.errorRows()).isNotEmpty();
    ErrorRowDto bad = pv.errorRows().stream()
        .filter(r -> ConfigPackageExcelValidator.CHANNEL_SHEET.equals(r.sheetName()))
        .findFirst()
        .orElseThrow();
    assertThat(bad.rowNo()).isEqualTo(2);
    assertThat(bad.values()).containsEntry("channel_code", "excel_patch_local");
    assertThat(bad.messages()).isNotEmpty();

    // 2) 内联编辑:补上 channel_name → 重校验该行通过
    TenantConfigPackageExcelPreviewResponse patched = service.patchRow(
        token,
        pv.previewToken(),
        ConfigPackageExcelValidator.CHANNEL_SHEET,
        2,
        Map.of("channel_name", "Fixed Channel"));
    assertThat(patched.previewToken()).isNotEqualTo(pv.previewToken());
    assertThat(patched.errorRows())
        .noneMatch(r -> ConfigPackageExcelValidator.CHANNEL_SHEET.equals(r.sheetName()));
    assertThat(patched.invalidRows()).isLessThan(pv.invalidRows());
  }

  @Test
  @DisplayName("传入不存在的列键被忽略: 不凭空塞键也不抛异常,出错行仍然保留")
  void shouldIgnoreUnknownColumnKeyOnPatch() {
    String tenantId = "excel-patch-tb";
    String token = importStore.save(session(tenantId, List.of(channelRow(tenantId, ""))));
    TenantConfigPackageExcelPreviewResponse preview = service.preview(token);
    // 传一个该行不存在的列键 → 忽略,不凭空塞键、不抛错
    TenantConfigPackageExcelPreviewResponse patched = service.patchRow(
        token,
        preview.previewToken(),
        ConfigPackageExcelValidator.CHANNEL_SHEET,
        2,
        Map.of("not_a_real_column", "x"));
    assertThat(patched.errorRows())
        .anyMatch(r -> ConfigPackageExcelValidator.CHANNEL_SHEET.equals(r.sheetName()));
  }

  @Test
  @DisplayName("行号早于表头: 拒绝并返回参数非法,不出现整数下溢")
  void shouldRejectRowNumberBeforeHeaderWithoutIntegerUnderflow() {
    String tenantId = "excel-patch-tc";
    String token = importStore.save(session(tenantId, List.of(channelRow(tenantId, "Channel"))));
    TenantConfigPackageExcelPreviewResponse preview = service.preview(token);

    assertThatThrownBy(() -> service.patchRow(
            token,
            preview.previewToken(),
            ConfigPackageExcelValidator.CHANNEL_SHEET,
            Integer.MIN_VALUE,
            Map.of()))
        .hasMessage("error.common.invalid_argument_detail");
  }

  @Test
  @DisplayName("旧预览不能覆盖较新的预览或行修改")
  void shouldRejectPatchWithStalePreviewToken() {
    String tenantId = "excel-patch-stale";
    String token = importStore.save(session(tenantId, List.of(channelRow(tenantId, ""))));
    TenantConfigPackageExcelPreviewResponse stale = service.preview(token);
    TenantConfigPackageExcelPreviewResponse current = service.preview(token);

    assertThatThrownBy(() -> service.patchRow(
            token,
            stale.previewToken(),
            ConfigPackageExcelValidator.CHANNEL_SHEET,
            2,
            Map.of("channel_name", "Stale")))
        .hasMessage("error.config_package.preview_stale");

    TenantConfigPackageExcelPreviewResponse patched = service.patchRow(
        token,
        current.previewToken(),
        ConfigPackageExcelValidator.CHANNEL_SHEET,
        2,
        Map.of("channel_name", "Current"));
    assertThat(patched.errorRows())
        .noneMatch(row -> ConfigPackageExcelValidator.CHANNEL_SHEET.equals(row.sheetName()));
  }

  @Test
  @DisplayName("不同 Console 实例共享加密会话并以原子预览版本控制修改和 Apply")
  void shouldShareEncryptedSessionAndComparePreviewAcrossInstances() {
    RedisTenantConfigExcelStore firstReplica =
        new RedisTenantConfigExcelStore(redisTemplate, objectMapper, cryptoService);
    RedisTenantConfigExcelStore secondReplica =
        new RedisTenantConfigExcelStore(redisTemplate, objectMapper, cryptoService);
    String token = firstReplica.save(session("excel-shared", List.of()));
    String redisKey = "batch:console:tenant-config-upload:" + token;
    try {
      String payload = redisTemplate.<String, String>opsForHash().get(redisKey, "payload");
      assertThat(cryptoService.isEncryptedContent(Base64.getDecoder().decode(payload)))
          .isTrue();
      assertThat(secondReplica.get(token).tenantId()).isEqualTo("excel-shared");

      String staleToken = firstReplica.issuePreviewSnapshot(token, 10L).previewToken();
      TenantConfigPackageExcelImportStore.PreviewSnapshot currentPreview =
          secondReplica.issuePreviewSnapshot(token, 11L);
      String currentToken = currentPreview.previewToken();
      assertThat(currentPreview.configurationRevision()).isEqualTo(11L);
      assertThat(firstReplica.claimForApply(token, staleToken, "apply-stale")).isFalse();
      assertThat(firstReplica.replaceSession(
              token, staleToken, session("excel-shared", List.of()), "replacement-preview"))
          .isFalse();
      assertThat(secondReplica.replaceSession(
              token,
              currentToken,
              session("excel-shared-updated", List.of()),
              "replacement-preview",
              12L))
          .isTrue();
      assertThat(firstReplica.get(token).tenantId()).isEqualTo("excel-shared-updated");

      assertThat(firstReplica.claimForApply(token, currentToken, "apply-old")).isFalse();
      assertThat(secondReplica.claimForApply(token, "replacement-preview", "apply-current"))
          .isTrue();
      assertThat(firstReplica.configurationRevisionForApply(token, "apply-current"))
          .isEqualTo(12L);
      assertThat(firstReplica.claimForApply(token, "replacement-preview", "apply-third"))
          .isFalse();
      firstReplica.releaseApply(token, "apply-old");
      firstReplica.completeApply(token, "apply-old");
      assertThat(firstReplica.isApplyInProgress(token)).isTrue();
      secondReplica.releaseApply(token, "apply-current");
    } finally {
      secondReplica.remove(token);
    }
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
