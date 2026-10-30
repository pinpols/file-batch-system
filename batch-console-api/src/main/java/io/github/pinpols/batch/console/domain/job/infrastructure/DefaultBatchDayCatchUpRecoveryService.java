package io.github.pinpols.batch.console.domain.job.infrastructure;

import io.github.pinpols.batch.common.utils.ConsoleTextSanitizer;
import io.github.pinpols.batch.console.domain.job.application.BatchDayCatchUpRecoveryService;
import io.github.pinpols.batch.console.domain.job.application.ConsoleJobApprovalService;
import io.github.pinpols.batch.console.domain.job.application.contract.response.BatchDayCatchUpRequestStatusResponse;
import io.github.pinpols.batch.console.shared.query.TenantIdResolver;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DefaultBatchDayCatchUpRecoveryService implements BatchDayCatchUpRecoveryService {
  private final TenantIdResolver tenantGuard;
  private final BatchDayCatchUpReconciliation reconciliation;
  private final ConsoleJobApprovalService approvalService;
  private final BatchDayCatchUpOperationStore operationStore;

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public BatchDayCatchUpRequestStatusResponse abandonUnprepared(
      String tenantId, String requestKey, LocalDate bizDate, String calendarCode) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    String safeCalendar = ConsoleTextSanitizer.safeInput(calendarCode, 128);
    operationStore.abandonUnprepared(resolvedTenant, requestKey, bizDate.toString(), safeCalendar);
    return approvalService.batchDayCatchUpStatus(resolvedTenant, requestKey);
  }

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public BatchDayCatchUpRequestStatusResponse continueRequest(String tenantId, String requestKey) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    approvalService.continueBatchDay(resolvedTenant, requestKey);
    return approvalService.batchDayCatchUpStatus(resolvedTenant, requestKey);
  }

  @Override
  @Transactional(propagation = Propagation.NEVER)
  public BatchDayCatchUpRequestStatusResponse reconcile(
      String tenantId, String requestKey, String jobCode) {
    String resolvedTenant = tenantGuard.resolveTenant(tenantId);
    reconciliation.reconcileOriginal(resolvedTenant, requestKey, jobCode);
    return approvalService.batchDayCatchUpStatus(resolvedTenant, requestKey);
  }
}
