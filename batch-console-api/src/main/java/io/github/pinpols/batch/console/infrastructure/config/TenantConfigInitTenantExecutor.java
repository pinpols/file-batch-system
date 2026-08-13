package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.console.infrastructure.config.DefaultConsoleTenantConfigInitApplicationService.StrictBundleAbortedException;
import io.github.pinpols.batch.console.infrastructure.config.TenantConfigInitApplyHandlers.ApplyContext;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse.ItemStats;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单租户配置包事务执行器。
 *
 * <p>跨租户批处理不能共享一个事务：某个租户 strict 回滚时，其他租户已经完成的初始化必须保留。显式跨 Bean 调用同时避免依赖 self-injection
 * 才能激活事务代理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TenantConfigInitTenantExecutor {

  private final TenantConfigInitApplyHandlers applyHandlers;

  @Transactional
  TenantInitResult execute(
      String tenantId, TenantConfigBatchInitRequest request, String operator, boolean dryRun) {
    InitMode mode = Nullables.coalesce(request.getMode(), InitMode.SKIP_EXISTING);
    ApplyContext ctx = new ApplyContext(tenantId, mode, operator, dryRun);
    ItemStats jobStats;
    ItemStats workflowStats;
    ItemStats pipelineStats;
    ItemStats channelStats;
    ItemStats templateStats;
    ItemStats queueStats;
    ItemStats windowStats;
    ItemStats calendarStats;
    ItemStats quotaStats;
    ItemStats alertStats;
    try {
      jobStats = applyHandlers.applyJobDefinitions(request.getJobDefinitions(), ctx);
      workflowStats = applyHandlers.applyWorkflowDefinitions(request.getWorkflowDefinitions(), ctx);
      pipelineStats = applyHandlers.applyPipelineDefinitions(request.getPipelineDefinitions(), ctx);
      channelStats = applyHandlers.applyFileChannels(request.getFileChannels(), ctx);
      templateStats = applyHandlers.applyFileTemplates(request.getFileTemplates(), ctx);
      queueStats = applyHandlers.applyResourceQueues(request.getResourceQueues(), ctx);
      windowStats = applyHandlers.applyBatchWindows(request.getBatchWindows(), ctx);
      calendarStats = applyHandlers.applyBusinessCalendars(request.getBusinessCalendars(), ctx);
      quotaStats = applyHandlers.applyQuotaPolicies(request.getQuotaPolicies(), ctx);
      alertStats = applyHandlers.applyAlertRoutings(request.getAlertRoutings(), ctx);
    } catch (Exception ex) {
      log.warn("[TenantConfigBatchInit] failed for tenant={}: {}", tenantId, ex.getMessage());
      return TenantInitResult.failed(tenantId, ex.getMessage());
    }
    if (request.isStrict()) {
      int totalFailed = jobStats.failed()
          + workflowStats.failed()
          + pipelineStats.failed()
          + channelStats.failed()
          + templateStats.failed()
          + queueStats.failed()
          + windowStats.failed()
          + calendarStats.failed()
          + quotaStats.failed()
          + alertStats.failed();
      if (totalFailed > 0) {
        throw new StrictBundleAbortedException(
            "strict bundle aborted: " + totalFailed + " spec(s) failed for tenant=" + tenantId);
      }
    }
    return new TenantInitResult(
        tenantId,
        true,
        null,
        jobStats,
        workflowStats,
        pipelineStats,
        channelStats,
        templateStats,
        queueStats,
        windowStats,
        calendarStats,
        quotaStats,
        alertStats);
  }
}
