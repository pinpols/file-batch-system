package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigCopyService;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncBundlePayload;
import io.github.pinpols.batch.console.application.contract.request.config.ConfigSyncImportRequest;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncLogResponse;
import io.github.pinpols.batch.console.application.contract.response.config.ConfigSyncSummaryResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemStats;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
import io.github.pinpols.batch.console.mapper.ConfigSyncLogMapper;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadata;
import io.github.pinpols.batch.console.support.web.ConsoleRequestMetadataResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置同步审计恢复: 原目标收据、缺失保持未知、重复提交与租户身份")
class ConfigSyncAuditRecoveryTest {

  @Mock private ConsoleTenantGuard tenantGuard;
  @Mock private ConsoleTenantConfigCopyService copyService;
  @Mock private ConfigSyncImportExecutor importExecutor;
  @Mock private ConsoleRequestMetadataResolver metadataResolver;
  @Mock private ConfigSyncLogMapper mapper;
  @Mock private PlatformTransactionManager transactionManager;
  @InjectMocks private DefaultConsoleConfigSyncApplicationService service;

  @Test
  @DisplayName("已提交目标按原始收据补审计且不再次执行导入")
  void shouldReconcileOriginalCounts_whenAllReceiptsExist() {
    prepareIdentity();
    Map<String, Object> running = log("RUNNING");
    Map<String, Object> finished = log("SUCCESS");
    when(mapper.selectByIdForUpdate("audit", 42L)).thenReturn(running).thenReturn(finished);
    when(importExecutor.readReceipts("audit", 42L, List.of("target")))
        .thenReturn(List.of(created("target")));
    when(mapper.updateResult(any())).thenReturn(1);

    ConfigSyncLogResponse result = service.reconcile("audit", 42L);

    assertThat(result.syncStatus()).isEqualTo("SUCCESS");
    ArgumentCaptor<Map<String, Object>> update = ArgumentCaptor.captor();
    verify(mapper).updateResult(update.capture());
    assertThat(update.getValue()).containsEntry("successItems", 1)
        .containsEntry("failedItems", 0).containsEntry("skippedItems", 0)
        .containsEntry("mergeDetail", true);
    String detail = update.getValue().get("detailJson").toString();
    assertThat(detail).contains("\"batchOperationId\":\"original-operation\"",
        "\"reconciledBy\":\"authenticated-operator\"", "\"created\":1");
    verify(importExecutor, never()).execute(any(), any());
  }

  @Test
  @DisplayName("已确认回滚的目标按失败收据终结而不是永久等待")
  void shouldReconcileFailure_whenRollbackReceiptExists() {
    prepareIdentity();
    when(mapper.selectByIdForUpdate("audit", 42L))
        .thenReturn(log("RUNNING")).thenReturn(log("PARTIAL_FAILED"));
    TenantInitResult failed = TenantInitResult.failed("target", "error.common.system_error");
    when(importExecutor.readReceipts("audit", 42L, List.of("target")))
        .thenReturn(List.of(failed));
    when(mapper.updateResult(any())).thenReturn(1);

    service.reconcile("audit", 42L);

    ArgumentCaptor<Map<String, Object>> update = ArgumentCaptor.captor();
    verify(mapper).updateResult(update.capture());
    assertThat(update.getValue()).containsEntry("syncStatus", "PARTIAL_FAILED")
        .containsEntry("successItems", 0).containsEntry("failedItems", 1);
  }

  @Test
  @DisplayName("缺少任何目标收据时继续保持未确认且不推断未执行")
  void shouldKeepRunning_whenReceiptIsMissing() {
    prepareIdentity();
    when(mapper.selectByIdForUpdate("audit", 42L)).thenReturn(log("RUNNING"));
    when(importExecutor.readReceipts("audit", 42L, List.of("target"))).thenReturn(List.of());

    ConfigSyncLogResponse result = service.reconcile("audit", 42L);

    assertThat(result.syncStatus()).isEqualTo("RUNNING");
    verify(mapper, never()).updateResult(any());
    verify(importExecutor, never()).execute(any(), any());
  }

  @Test
  @DisplayName("同一日志重复对账返回原终态且不重新读取或改写目标")
  void shouldReturnTerminalAudit_whenReconciledAgain() {
    prepareIdentity();
    when(mapper.selectByIdForUpdate("audit", 42L)).thenReturn(log("SUCCESS"));

    ConfigSyncLogResponse result = service.reconcile("audit", 42L);

    assertThat(result.syncStatus()).isEqualTo("SUCCESS");
    verify(importExecutor, never()).readReceipts(anyString(), any(), any());
    verify(importExecutor, never()).execute(any(), any());
    verify(mapper, never()).updateResult(any());
  }

  @Test
  @DisplayName("错误审计租户下不存在日志时不读取其它租户收据")
  void shouldRejectCrossTenantLog_whenAuditIdentityDoesNotMatch() {
    prepareIdentity();
    when(mapper.selectByIdForUpdate("audit", 42L)).thenReturn(null);

    assertThatThrownBy(() -> service.reconcile("audit", 42L))
        .isInstanceOfSatisfying(BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.NOT_FOUND));

    verify(importExecutor, never()).readReceipts(anyString(), any(), any());
  }

  @Test
  @DisplayName("收据目标与原计划不一致时拒绝最终审计")
  void shouldRejectReceiptMismatch_whenStoredTargetDiffers() {
    prepareIdentity();
    when(mapper.selectByIdForUpdate("audit", 42L)).thenReturn(log("RUNNING"));
    when(importExecutor.readReceipts("audit", 42L, List.of("target")))
        .thenReturn(List.of(created("another-target")));

    assertThatThrownBy(() -> service.reconcile("audit", 42L))
        .isInstanceOf(IllegalStateException.class);

    verify(mapper, never()).updateResult(any());
  }

  @Test
  @DisplayName("最终审计失败后原请求键仍定位原日志且不重复执行目标")
  void shouldRejectDuplicateImport_whenInitialAuditOwnsRequestKey() {
    prepareIdentity();
    ConfigSyncImportRequest request = new ConfigSyncImportRequest();
    request.setTenantId("audit");
    request.setTargetTenantIds(List.of("target"));
    request.setBundle(new ConfigSyncBundlePayload());
    when(mapper.insert(any())).thenThrow(new DuplicateKeyException("test duplicate"));
    when(mapper.selectIdByRequestKey(anyString(), anyString())).thenReturn(42L);

    assertThatThrownBy(() -> service.importBundle(request))
        .isInstanceOfSatisfying(BizException.class,
            failure -> assertThat(failure.getCode()).isEqualTo(ResultCode.CONFLICT));

    ArgumentCaptor<Map<String, Object>> inserted = ArgumentCaptor.captor();
    verify(mapper).insert(inserted.capture());
    String detail = inserted.getValue().get("detailJson").toString();
    assertThat(detail).contains("requestKeyHash").doesNotContain("secret-idempotency-key");
    ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
    verify(mapper).selectIdByRequestKey(eq("audit"), hash.capture());
    assertThat(hash.getValue()).matches("[0-9a-f]{64}");
    verify(importExecutor, never()).execute(any(), any());
  }

  private void prepareIdentity() {
    when(tenantGuard.resolveTenant("audit")).thenReturn("audit");
    ConsoleRequestMetadata metadata = new ConsoleRequestMetadata(
        null, null, "audit", "authenticated-operator", "secret-idempotency-key", null);
    when(metadataResolver.current()).thenReturn(metadata);
  }

  private static Map<String, Object> log(String status) {
    ConfigSyncSummaryResponse summary = new ConfigSyncSummaryResponse(1, 0, 0, 0, 0, 0, 0, 0, 0);
    Map<String, Object> detail = Map.of(
        "batchOperationId", "original-operation", "summary", summary,
        "dryRun", false, "targetTenantIds", List.of("target"));
    String detailJson = JsonUtils.toJson(detail);
    return Map.of("id", 42L, "tenantId", "audit", "syncStatus", status, "detailJson", detailJson);
  }

  static TenantInitResult created(String tenantId) {
    ItemStats empty = ItemStats.empty();
    ItemStats created = new ItemStats(1, 0, 0, 0, 0, List.of());
    return new TenantInitResult(
        tenantId, true, null, created, empty, empty, empty, empty, empty, empty, empty, empty);
  }
}
