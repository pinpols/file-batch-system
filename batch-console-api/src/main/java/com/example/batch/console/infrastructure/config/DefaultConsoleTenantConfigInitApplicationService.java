package com.example.batch.console.infrastructure.config;

import com.example.batch.common.utils.Nullables;
import com.example.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import com.example.batch.console.infrastructure.config.TenantConfigInitApplyHandlers.ApplyContext;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest;
import com.example.batch.console.web.request.config.TenantConfigBatchInitRequest.InitMode;
import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse;
import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse.ItemStats;
import com.example.batch.console.web.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 跨租户批量配置初始化入口，被 {@link DefaultConsoleConfigSyncApplicationService#importBundle} 和 直接 HTTP 入口两条路调用。
 *
 * <p><b>权限边界</b>：直接操作 Mapper 层，主动绕过租户守卫——调用方须在进入本服务前完成 ROLE_ADMIN 校验， 本服务不再重复验证。
 *
 * <p><b>10 种配置类型</b>：job / workflow / pipeline / fileChannel / fileTemplate / resourceQueue /
 * batchWindow / businessCalendar / quotaPolicy / alertRouting，每种类型由 {@link
 * TenantConfigInitApplyHandlers} 中独立的 {@code apply*} 方法处理(P2-3 god-class-decomposition extract,
 * 2026-04-30 抽出),公共"查找 → 跳过/更新/创建"循环也在 handler 内部统一驱动并逐项隔离异常(单项失败不中断全批)。
 *
 * <p><b>InitMode</b>:
 *
 * <ul>
 *   <li>{@code SKIP_EXISTING}(默认) — 已存在则记为 skipped,适合首次初始化。
 *   <li>{@code UPSERT} — 已存在则覆盖更新,适合跨环境同步。
 * </ul>
 *
 * <p><b>dryRun</b>:所有 insert/update 被跳过,仅做 find + 计数,用于 ConfigSync preview 预判结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultConsoleTenantConfigInitApplicationService
    implements ConsoleTenantConfigInitApplicationService {

  private final TenantConfigInitApplyHandlers applyHandlers;

  @Lazy @Autowired private DefaultConsoleTenantConfigInitApplicationService self;

  @Override
  public TenantConfigBatchInitResponse batchInit(
      TenantConfigBatchInitRequest request, String operator, String batchOperationId) {
    boolean dryRun = request.isDryRun();
    List<TenantInitResult> results = new ArrayList<>();
    int successCount = 0;
    int failureCount = 0;

    for (String tenantId : request.getTargetTenantIds()) {
      try {
        TenantInitResult result = self.initForTenant(tenantId, request, operator, dryRun);
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
            tenantId,
            batchOperationId,
            ex.getMessage());
        results.add(TenantInitResult.failed(tenantId, ex.getMessage()));
        failureCount++;
      } catch (Exception ex) {
        log.error(
            "[TenantConfigBatchInit] unexpected error for tenant={} batchOp={}",
            tenantId,
            batchOperationId,
            ex);
        results.add(TenantInitResult.failed(tenantId, ex.getMessage()));
        failureCount++;
      }
    }

    return new TenantConfigBatchInitResponse(
        batchOperationId,
        request.getTargetTenantIds().size(),
        successCount,
        failureCount,
        dryRun,
        results);
  }

  @Transactional
  protected TenantInitResult initForTenant(
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
    // strict=true (Job Bundle 跨环境导入)：任一 spec failed 即抛出 StrictBundleAbortedException,
    // 由 @Transactional 触发整体回滚 → all-or-nothing。SKIP_EXISTING 跳过 / UPSERT 覆盖不算 failed。
    if (request.isStrict()) {
      int totalFailed =
          jobStats.failed()
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

  /** strict 模式下任一 spec failed 触发,@Transactional 自动回滚后由 batchInit 转成 failed result。 */
  static final class StrictBundleAbortedException extends RuntimeException {
    StrictBundleAbortedException(String message) {
      super(message);
    }
  }
}
