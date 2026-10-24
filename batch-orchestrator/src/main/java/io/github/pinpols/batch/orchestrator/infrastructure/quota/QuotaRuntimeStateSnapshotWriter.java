package io.github.pinpols.batch.orchestrator.infrastructure.quota;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.scheduler.QuotaRuntimeStateService;
import io.github.pinpols.batch.orchestrator.domain.entity.QuotaRuntimeStateEntity;
import io.github.pinpols.batch.orchestrator.mapper.QuotaRuntimeStateMapper;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 在独立事务边界内把单个 Redis 配额快照写入 PG，避免调度器依赖同类代理调用。 */
@Slf4j
@Component
@RequiredArgsConstructor
class QuotaRuntimeStateSnapshotWriter {

  private final QuotaRuntimeStateService quotaRuntimeStateService;
  private final QuotaRuntimeStateMapper quotaRuntimeStateMapper;

  /** 窗口活跃且 peak 大于零时 upsert；空窗口不落库，避免周期任务持续制造无价值行。 */
  @Transactional
  int writeIfActive(
      String tenantId, String scope, String ownerCode, String policy, int burstLimit) {
    if (burstLimit <= 0) {
      return 0;
    }
    QuotaRuntimeStateService.QuotaReservationOwner owner =
        new QuotaRuntimeStateService.QuotaReservationOwner(tenantId, scope, ownerCode);
    QuotaRuntimeStateService.QuotaDescribeRequest request =
        new QuotaRuntimeStateService.QuotaDescribeRequest(owner, policy, burstLimit, 24);
    QuotaRuntimeStateService.QuotaRuntimeSnapshot snap =
        quotaRuntimeStateService.describe(request);
    boolean hasNoActiveBorrowedQuota = snap == null
        || snap.peakBorrowedCount() == null
        || snap.peakBorrowedCount() == 0
        || snap.windowExpiresAt() == null;
    if (hasNoActiveBorrowedQuota) {
      return 0;
    }
    Instant now = BatchDateTimeSupport.utcNow();
    QuotaRuntimeStateEntity existing =
        quotaRuntimeStateMapper.selectByTenantQuotaScopeOwner(tenantId, scope, ownerCode);
    if (existing == null) {
      QuotaRuntimeStateEntity toInsert = QuotaRuntimeStateEntity.builder()
          .tenantId(tenantId)
          .quotaScope(scope)
          .ownerCode(ownerCode)
          .quotaResetPolicy(snap.quotaResetPolicy())
          .windowStartedAt(snap.windowStartedAt())
          .windowExpiresAt(snap.windowExpiresAt())
          .peakBorrowedCount(snap.peakBorrowedCount())
          .lastResetAt(snap.lastResetAt())
          .createdAt(now)
          .updatedAt(now)
          .build();
      quotaRuntimeStateMapper.insert(toInsert);
      return 1;
    }
    QuotaRuntimeStateEntity toUpdate = existing.withRefresh(
        snap.quotaResetPolicy(),
        snap.windowStartedAt(),
        snap.windowExpiresAt(),
        snap.peakBorrowedCount(),
        snap.lastResetAt());
    int rows = quotaRuntimeStateMapper.updateWithCas(toUpdate);
    if (rows == 0) {
      log.debug(
          "quota snapshot CAS conflict, skipping: tenantId={}, scope={}, owner={}",
          tenantId,
          scope,
          ownerCode);
      return 0;
    }
    return 1;
  }
}
