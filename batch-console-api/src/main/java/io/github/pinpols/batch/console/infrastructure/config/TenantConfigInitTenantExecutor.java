package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Nullables;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.InitMode;
import io.github.pinpols.batch.console.application.contract.request.config.TenantConfigBatchInitRequest.JobDefinitionSpec;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.ItemStats;
import io.github.pinpols.batch.console.application.contract.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import io.github.pinpols.batch.console.infrastructure.config.DefaultConsoleTenantConfigInitApplicationService.StrictBundleAbortedException;
import io.github.pinpols.batch.console.infrastructure.config.TenantConfigInitApplyHandlers.ApplyContext;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

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
  private final ConsoleConfigCacheInvalidationService cacheInvalidationService;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  TenantInitResult execute(
      String tenantId, TenantConfigBatchInitRequest request, String operator, boolean dryRun) {
    return applyWithinTransaction(tenantId, request, operator, dryRun);
  }

  TenantInitResult applyWithinTransaction(
      String tenantId, TenantConfigBatchInitRequest request, String operator, boolean dryRun) {
    applyHandlers.bindTenantContext(tenantId);
    InitMode mode = Nullables.coalesce(request.getMode(), InitMode.SKIP_EXISTING);
    ApplyContext ctx = new ApplyContext(tenantId, mode, operator, dryRun);
    ItemStats jobStats;
    ItemStats workflowStats;
    ItemStats channelStats;
    ItemStats templateStats;
    ItemStats queueStats;
    ItemStats windowStats;
    ItemStats calendarStats;
    ItemStats quotaStats;
    ItemStats alertStats;
    List<JobDefinitionSpec> jobSpecs;
    try {
      calendarStats = applyHandlers.applyBusinessCalendars(request.getBusinessCalendars(), ctx);
      jobSpecs = copyJobSpecs(request.getJobDefinitions());
      jobStats = applyHandlers.applyJobDefinitions(jobSpecs, ctx);
      workflowStats = applyHandlers.applyWorkflowDefinitions(request.getWorkflowDefinitions(), ctx);
      if (!dryRun) {
        applyHandlers.ensureManagedSingleJobWorkflows(jobSpecs, tenantId);
      }
      channelStats = applyHandlers.applyFileChannels(request.getFileChannels(), ctx);
      templateStats = applyHandlers.applyFileTemplates(request.getFileTemplates(), ctx);
      queueStats = applyHandlers.applyResourceQueues(request.getResourceQueues(), ctx);
      windowStats = applyHandlers.applyBatchWindows(request.getBatchWindows(), ctx);
      quotaStats = applyHandlers.applyQuotaPolicies(request.getQuotaPolicies(), ctx);
      alertStats = applyHandlers.applyAlertRoutings(request.getAlertRoutings(), ctx);
    } catch (Exception ex) {
      log.warn(
          "[TenantConfigBatchInit] failed for tenant={}: {}",
          LogSanitizer.value(tenantId),
          SwallowedExceptionLogger.summary(ex));
      // 拒绝闭合环路的那条边，严格模式会回滚该租户事务。
      TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
      String failureMessage = TenantConfigFailureMessages.forFailure(ex);
      return TenantInitResult.failed(tenantId, failureMessage);
    }
    if (request.isStrict()) {
      int totalFailed = jobStats.failed()
          + workflowStats.failed()
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
    invalidateRuntimeCaches(
        tenantId,
        dryRun,
        new ItemStatsBundle(
            jobStats, workflowStats, windowStats, calendarStats, quotaStats, queueStats),
        !EmptyChecks.isEmpty(jobSpecs));
    return TenantInitResult.builder()
        .tenantId(tenantId)
        .success(true)
        .errorMessage(null)
        .jobDefinitions(jobStats)
        .workflowDefinitions(workflowStats)
        .fileChannels(channelStats)
        .fileTemplates(templateStats)
        .resourceQueues(queueStats)
        .batchWindows(windowStats)
        .businessCalendars(calendarStats)
        .quotaPolicies(quotaStats)
        .alertRoutings(alertStats)
        .build();
  }

  private record ItemStatsBundle(
      ItemStats jobStats,
      ItemStats workflowStats,
      ItemStats windowStats,
      ItemStats calendarStats,
      ItemStats quotaStats,
      ItemStats queueStats) {}

  private void invalidateRuntimeCaches(
      String tenantId,
      boolean dryRun,
      ItemStatsBundle stats,
      boolean managedWorkflowProvisioningMayRun) {
    ItemStats jobStats = stats.jobStats();
    ItemStats workflowStats = stats.workflowStats();
    ItemStats windowStats = stats.windowStats();
    ItemStats calendarStats = stats.calendarStats();
    ItemStats quotaStats = stats.quotaStats();
    ItemStats queueStats = stats.queueStats();
    if (dryRun) {
      return;
    }
    if (hasChanges(jobStats)) {
      cacheInvalidationService.evictAllJobDefinitions(tenantId);
    }
    if (hasChanges(workflowStats) || managedWorkflowProvisioningMayRun) {
      cacheInvalidationService.evictAllWorkflowDefinitions(tenantId);
    }
    if (hasChanges(windowStats)) {
      cacheInvalidationService.evictAllBatchWindows(tenantId);
    }
    if (hasChanges(calendarStats)) {
      cacheInvalidationService.evictAllBusinessCalendars(tenantId);
    }
    if (hasChanges(quotaStats)) {
      cacheInvalidationService.evictQuotaPolicies(tenantId);
    }
    if (hasChanges(windowStats) || hasChanges(calendarStats) || hasChanges(queueStats)) {
      cacheInvalidationService.evictMetaOptions(tenantId);
    }
  }

  private static boolean hasChanges(ItemStats stats) {
    return stats.created() > 0 || stats.updated() > 0 || stats.migrated() > 0;
  }

  private static List<JobDefinitionSpec> copyJobSpecs(List<JobDefinitionSpec> source) {
    if (EmptyChecks.isNull(source) || EmptyChecks.isEmpty(source)) {
      return new ArrayList<>();
    }
    List<JobDefinitionSpec> copies = new ArrayList<>(source.size());
    for (JobDefinitionSpec spec : source) {
      JobDefinitionSpec copy = new JobDefinitionSpec();
      BeanUtils.copyProperties(spec, copy);
      copies.add(copy);
    }
    return copies;
  }
}
