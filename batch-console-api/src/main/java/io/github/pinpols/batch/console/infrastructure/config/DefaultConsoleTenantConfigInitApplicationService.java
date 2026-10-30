package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 跨租户配置初始化协调入口，供直接初始化、作业包导入及配置同步调用。
 *
 * <p>调用方负责管理员授权，本服务先校验全部目标身份，再由执行器建立逐租户事务。支持作业、Workflow、文件渠道、文件模板、
 * 资源队列、批量窗口、业务日历、配额及告警路由九类配置，具体应用逻辑由 {@link TenantConfigInitApplyHandlers} 管理。
 *
 * <p>默认模式只创建缺失项，UPSERT 更新已有项；dryRun 只读取和计数。严格包的已知回滚逐租户隔离，提交结果未知时停止后续目标并向上传播。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultConsoleTenantConfigInitApplicationService
    implements ConsoleTenantConfigInitApplicationService {

  private final TenantConfigInitTenantExecutor tenantExecutor;

  @Override
  public TenantConfigBatchInitResponse batchInit(
      TenantConfigBatchInitRequest request, String operator, String batchOperationId) {
    List<String> targetTenantIds = request.getTargetTenantIds();
    // 在首个租户事务开始前校验全部目标，避免参数错误发生在其他租户已提交之后。
    boolean invalidTargets = EmptyChecks.isNull(targetTenantIds)
        || EmptyChecks.isEmpty(targetTenantIds)
        || targetTenantIds.stream().anyMatch(EmptyChecks::isBlank);
    if (invalidTargets) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.invalid_argument");
    }
    boolean dryRun = request.isDryRun();
    List<TenantInitResult> results = new ArrayList<>();
    int successCount = 0;
    int failureCount = 0;

    for (String tenantId : targetTenantIds) {
      // 只有明确回滚才能记为失败；事务提交或连接异常可能发生在提交成功后，必须停止并向上传播。
      try {
        TenantInitResult result = RlsTenantContextHolder.runWithTenant(
            tenantId, () -> tenantExecutor.execute(tenantId, request, operator, dryRun));
        results.add(result);
        if (result.success()) {
          successCount++;
        } else {
          failureCount++;
        }
      } catch (StrictBundleAbortedException ex) {
        // strict=true 且任一 spec failed → @Transactional 已回滚,组装 failed 结果让前端看到原因
        log.warn(
            "[TenantConfigBatchInit] strict bundle rolled back for tenant={} batchOp={}: {}",
            LogSanitizer.value(tenantId),
            LogSanitizer.value(batchOperationId),
            SwallowedExceptionLogger.summary(ex));
        String failureMessage = TenantConfigFailureMessages.forFailure(ex);
        results.add(TenantInitResult.failed(tenantId, failureMessage));
        failureCount++;
      }
    }

    return new TenantConfigBatchInitResponse(
        batchOperationId, targetTenantIds.size(), successCount, failureCount, dryRun, results);
  }

  /** strict 模式下任一 spec failed 触发,@Transactional 自动回滚后由 batchInit 转成 failed result。 */
  static final class StrictBundleAbortedException extends RuntimeException {
    StrictBundleAbortedException(String message) {
      super(message);
    }
  }
}
