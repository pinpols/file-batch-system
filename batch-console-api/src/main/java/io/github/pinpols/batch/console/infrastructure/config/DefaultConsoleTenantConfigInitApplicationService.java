package io.github.pinpols.batch.console.infrastructure.config;

import io.github.pinpols.batch.console.application.config.ConsoleTenantConfigInitApplicationService;
import io.github.pinpols.batch.console.web.request.config.TenantConfigBatchInitRequest;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse;
import io.github.pinpols.batch.console.web.response.config.TenantConfigBatchInitResponse.TenantInitResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

  private final TenantConfigInitTenantExecutor tenantExecutor;

  @Override
  public TenantConfigBatchInitResponse batchInit(
      TenantConfigBatchInitRequest request, String operator, String batchOperationId) {
    boolean dryRun = request.isDryRun();
    List<TenantInitResult> results = new ArrayList<>();
    int successCount = 0;
    int failureCount = 0;

    for (String tenantId : request.getTargetTenantIds()) {
      try {
        TenantInitResult result = tenantExecutor.execute(tenantId, request, operator, dryRun);
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

  /** strict 模式下任一 spec failed 触发,@Transactional 自动回滚后由 batchInit 转成 failed result。 */
  static final class StrictBundleAbortedException extends RuntimeException {
    StrictBundleAbortedException(String message) {
      super(message);
    }
  }
}
