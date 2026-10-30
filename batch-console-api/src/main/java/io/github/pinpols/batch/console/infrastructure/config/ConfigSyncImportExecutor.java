package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.infrastructure.config.ConfigSyncTargetExecutor.Target;
import io.github.pinpols.batch.console.infrastructure.config.DefaultConsoleTenantConfigInitApplicationService.StrictBundleAbortedException;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 顺序处理原目标集合；无法确认提交结果时停止，后续只能读取持久收据对账。 */
@Component
@RequiredArgsConstructor
class ConfigSyncImportExecutor {

  private final ConfigSyncTargetExecutor targetExecutor;

  record ImportContext(
      String auditTenantId, Long logId, String operator, String batchOperationId) {}

  TenantConfigBatchInitResponse execute(
      ImportContext context, TenantConfigBatchInitRequest request) {
    List<TenantInitResult> results = new ArrayList<>();
    for (String tenantId : request.getTargetTenantIds()) {
      Target target = new Target(context.auditTenantId(), context.logId(), tenantId);
      TenantInitResult result;
      try {
        result = RlsTenantContextHolder.runWithTenant(
            tenantId, () -> targetExecutor.execute(target, request, context.operator()));
      } catch (StrictBundleAbortedException failure) {
        // 此异常只在目标事务主动回滚后传播，不能把一般提交/连接异常推断为未提交。
        String message = TenantConfigFailureMessages.forFailure(failure);
        result = TenantInitResult.failed(tenantId, message);
      }
      if (!result.success()) {
        TenantInitResult rolledBackResult = result;
        RlsTenantContextHolder.runWithTenant(tenantId, () -> {
          targetExecutor.recordRolledBack(target, rolledBackResult);
          return null;
        });
      }
      results.add(result);
    }
    return aggregate(context.batchOperationId(), request.isDryRun(), results);
  }

  List<TenantInitResult> readReceipts(
      String auditTenantId, Long logId, List<String> targetTenantIds) {
    List<TenantInitResult> results = new ArrayList<>();
    for (String tenantId : targetTenantIds) {
      Target target = new Target(auditTenantId, logId, tenantId);
      TenantInitResult result =
          RlsTenantContextHolder.runWithTenant(tenantId, () -> targetExecutor.findReceipt(target));
      if (EmptyChecks.isNotNull(result)) {
        results.add(result);
      }
    }
    return results;
  }

  static TenantConfigBatchInitResponse aggregate(
      String batchOperationId, boolean dryRun, List<TenantInitResult> results) {
    int successes = (int) results.stream().filter(TenantInitResult::success).count();
    return new TenantConfigBatchInitResponse(
        batchOperationId, results.size(), successes, results.size() - successes, dryRun, results);
  }
}
