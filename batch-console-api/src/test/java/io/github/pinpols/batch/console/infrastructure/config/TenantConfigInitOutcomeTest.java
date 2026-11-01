package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.console.application.config.ConfigBatchOperationContext;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.infrastructure.config.DefaultConsoleTenantConfigInitApplicationService.StrictBundleAbortedException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionSystemException;

/** 区分已知回滚与未知提交结果，避免把已生效配置报告为可安全重试的失败。 */
@DisplayName("租户配置批量初始化的事务结果边界")
@ExtendWith(MockitoExtension.class)
class TenantConfigInitOutcomeTest {

  @Mock
  private TenantConfigInitTenantExecutor executor;

  @Test
  @DisplayName("事务结果未知时向上传播并停止后续租户，恢复原租户上下文")
  void shouldStopRemainingTenants_whenCommitOutcomeIsUnknown() {
    TenantConfigBatchInitRequest request = request();
    TransactionSystemException failure = new TransactionSystemException("commit outcome unknown");
    when(executor.execute(eq("first"), eq(request), eq("admin"), eq(false))).thenThrow(failure);
    DefaultConsoleTenantConfigInitApplicationService service =
        new DefaultConsoleTenantConfigInitApplicationService(executor);

    RlsTenantContextHolder.runWithTenant("caller", () -> {
      assertThatThrownBy(() -> service.batchInit(request, "admin", "original-operation"))
          .isSameAs(failure);
      assertThat(RlsTenantContextHolder.get()).isEqualTo("caller");
      return null;
    });
    verify(executor, never()).execute(eq("second"), any(), any(), anyBoolean());
  }

  @Test
  @DisplayName("严格包已知回滚仍逐租户隔离，后续租户继续执行")
  void shouldContinueRemainingTenants_whenStrictBundleRolledBack() {
    TenantConfigBatchInitRequest request = request();
    when(executor.execute(eq("first"), eq(request), eq("admin"), eq(false)))
        .thenThrow(new StrictBundleAbortedException("strict bundle aborted"));
    TenantInitResult second =
        TenantInitResult.builder().tenantId("second").success(true).build();
    when(executor.execute(eq("second"), eq(request), eq("admin"), eq(false))).thenReturn(second);
    DefaultConsoleTenantConfigInitApplicationService service =
        new DefaultConsoleTenantConfigInitApplicationService(executor);

    TenantConfigBatchInitResponse result = service.batchInit(request, "admin", "known-rollback");

    assertThat(result.successTenants()).isOne();
    assertThat(result.failureTenants()).isOne();
    assertThat(result.results()).extracting(TenantInitResult::success).containsExactly(false, true);
  }

  @Test
  @DisplayName("可恢复操作保留重复目标顺序，明确回滚后记录失败并继续")
  void shouldRecordKnownRollbackAndPreserveDuplicateTargetOrder() {
    TenantConfigBatchInitRequest request = request();
    request.setTargetTenantIds(List.of("first", "second", "first"));
    ConfigBatchOperationContext context = new ConfigBatchOperationContext("audit", "operation");
    TenantInitResult success = TenantInitResult.builder().tenantId("first").success(true).build();
    TenantInitResult failed = TenantInitResult.failed("second", "处理失败");
    when(executor.executeRecoverable("first", request, "admin", context, 0)).thenReturn(success);
    when(executor.executeRecoverable("second", request, "admin", context, 1)).thenReturn(failed);
    when(executor.executeRecoverable("first", request, "admin", context, 2)).thenReturn(success);
    DefaultConsoleTenantConfigInitApplicationService service =
        new DefaultConsoleTenantConfigInitApplicationService(executor);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", context);

    assertThat(response.batchOperationId()).isEqualTo("operation");
    assertThat(response.results()).extracting(TenantInitResult::tenantId)
        .containsExactly("first", "second", "first");
    assertThat(response.successTenants()).isEqualTo(2);
    assertThat(response.failureTenants()).isEqualTo(1);
    InOrder calls = inOrder(executor);
    calls.verify(executor).executeRecoverable("first", request, "admin", context, 0);
    calls.verify(executor).executeRecoverable("second", request, "admin", context, 1);
    calls.verify(executor).recordRolledBack(context, 1, "second", failed);
    calls.verify(executor).executeRecoverable("first", request, "admin", context, 2);
  }

  @Test
  @DisplayName("严格包已回滚才记录失败，提交确认异常不记录失败或继续")
  void shouldRecordStrictRollbackButStopOnUnknownCommit() {
    TenantConfigBatchInitRequest request = request();
    ConfigBatchOperationContext context = new ConfigBatchOperationContext("audit", "operation");
    TransactionSystemException failure = new TransactionSystemException("commit outcome unknown");
    when(executor.executeRecoverable("first", request, "admin", context, 0))
        .thenThrow(new StrictBundleAbortedException("strict bundle aborted"));
    when(executor.executeRecoverable("second", request, "admin", context, 1)).thenThrow(failure);
    DefaultConsoleTenantConfigInitApplicationService service =
        new DefaultConsoleTenantConfigInitApplicationService(executor);

    assertThatThrownBy(() -> service.batchInit(request, "admin", context)).isSameAs(failure);

    verify(executor).recordRolledBack(eq(context), eq(0), eq("first"), any());
    verify(executor, never()).recordRolledBack(eq(context), eq(1), eq("second"), any());
  }

  @Test
  @DisplayName("预览仍使用原事务路径且不写逐目标回执")
  void shouldKeepDryRunReadOnly_whenOperationContextProvided() {
    TenantConfigBatchInitRequest request = request();
    request.setDryRun(true);
    ConfigBatchOperationContext context = new ConfigBatchOperationContext("audit", "operation");
    when(executor.execute(any(), eq(request), eq("admin"), eq(true))).thenAnswer(invocation -> {
      String tenantId = invocation.getArgument(0);
      return TenantInitResult.builder().tenantId(tenantId).success(true).build();
    });
    DefaultConsoleTenantConfigInitApplicationService service =
        new DefaultConsoleTenantConfigInitApplicationService(executor);

    TenantConfigBatchInitResponse response = service.batchInit(request, "admin", context);

    assertThat(response.dryRun()).isTrue();
    verify(executor, never()).executeRecoverable(any(), any(), any(), any(), anyInt());
    verify(executor, never()).recordRolledBack(any(), anyInt(), any(), any());
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" "})
  @DisplayName("目标身份非法时应用边界先拒绝，所有租户执行器零调用")
  void shouldValidateEveryTargetBeforeStartingAnyTenant(String invalidTarget) {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    List<String> targets = new ArrayList<>();
    targets.add("first");
    targets.add(invalidTarget);
    request.setTargetTenantIds(targets);
    // 为旧实现缺失校验时提供正常执行结果；正确实现必须在调用之前拒绝。
    lenient().when(executor.execute(any(), any(), any(), anyBoolean())).thenAnswer(invocation -> {
      String tenantId = invocation.getArgument(0);
      return TenantInitResult.builder().tenantId(tenantId).success(true).build();
    });
    DefaultConsoleTenantConfigInitApplicationService service =
        new DefaultConsoleTenantConfigInitApplicationService(executor);

    assertThatThrownBy(() -> service.batchInit(request, "admin", "invalid-target"))
        .isInstanceOfSatisfying(BizException.class, failure -> {
          assertThat(failure.getCode()).isEqualTo(ResultCode.INVALID_ARGUMENT);
          assertThat(failure.getMessageKey()).isEqualTo("error.common.invalid_argument");
        });
    verifyNoInteractions(executor);
  }

  private TenantConfigBatchInitRequest request() {
    TenantConfigBatchInitRequest request = new TenantConfigBatchInitRequest();
    request.setTargetTenantIds(List.of("first", "second"));
    return request;
  }
}
