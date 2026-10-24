package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.infrastructure.config.ConfigSyncImportExecutor.ImportContext;
import io.github.pinpols.batch.console.infrastructure.config.ConfigSyncTargetExecutor.Target;
import io.github.pinpols.batch.console.infrastructure.config.DefaultConsoleTenantConfigInitApplicationService.StrictBundleAbortedException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionSystemException;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置同步目标结果: 已确认回滚与提交未知必须分开处理")
class ConfigSyncImportExecutorTest {

  @Mock private ConfigSyncTargetExecutor targetExecutor;
  @InjectMocks private ConfigSyncImportExecutor service;

  @Test
  @DisplayName("目标事务主动回滚后独立保存失败收据并继续其它目标")
  void shouldRecordConfirmedRollback_whenTargetReportsFailure() {
    TenantConfigBatchInitRequest request = request();
    ImportContext context = new ImportContext("audit", 42L, "operator", "batch");
    Target target = new Target("audit", 42L, "target");
    TenantInitResult failed = TenantInitResult.failed("target", "error.common.system_error");
    when(targetExecutor.execute(target, request, "operator")).thenReturn(failed);

    TenantConfigBatchInitResponse result = service.execute(context, request);

    assertThat(result.failureTenants()).isOne();
    verify(targetExecutor).recordRolledBack(target, failed);
  }

  @Test
  @DisplayName("严格导入异常完成回滚后允许保存失败收据")
  void shouldRecordConfirmedRollback_whenStrictBundleIsAborted() {
    TenantConfigBatchInitRequest request = request();
    ImportContext context = new ImportContext("audit", 42L, "operator", "batch");
    when(targetExecutor.execute(any(), any(), anyString()))
        .thenThrow(new StrictBundleAbortedException("strict failure"));

    TenantConfigBatchInitResponse result = service.execute(context, request);

    assertThat(result.failureTenants()).isOne();
    verify(targetExecutor).recordRolledBack(any(), any());
  }

  @Test
  @DisplayName("提交响应丢失时不得把未知结果保存成失败收据")
  void shouldPreserveUnknownOutcome_whenCommitFails() {
    TenantConfigBatchInitRequest request = request();
    ImportContext context = new ImportContext("audit", 42L, "operator", "batch");
    when(targetExecutor.execute(any(), any(), anyString()))
        .thenThrow(new TransactionSystemException("commit outcome unknown"));

    assertThatThrownBy(() -> service.execute(context, request))
        .isInstanceOf(TransactionSystemException.class);

    verify(targetExecutor, never()).recordRolledBack(any(), any());
  }

  private static TenantConfigBatchInitRequest request() {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of("target"));
    return request;
  }
}
