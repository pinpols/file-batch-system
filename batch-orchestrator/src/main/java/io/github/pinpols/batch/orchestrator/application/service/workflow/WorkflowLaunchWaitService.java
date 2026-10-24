package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.domain.param.WorkflowLaunchWaitInsertParam;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowLaunchWaitClaim;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.orchestrator.mapper.WorkflowLaunchWaitMapper;
import io.github.pinpols.batch.orchestrator.service.LaunchRequestIdentityGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 确认启动消息前先持久化准入名额等待；首次请求载荷优先。 */
@Service
@RequiredArgsConstructor
public class WorkflowLaunchWaitService {
  private final WorkflowLaunchWaitMapper waitingMapper;
  private final TriggerRequestMapper triggerRequestMapper;

  @Transactional
  public void defer(LaunchRequest request) {
    waitingMapper.setTenantContext(request.tenantId());
    String lookupTenantId = request.tenantId();
    String lookupRequestId = request.requestId();
    TriggerRequestEntity pendingRequest =
        triggerRequestMapper.selectByTenantAndRequestIdForUpdate(lookupTenantId, lookupRequestId);
    LaunchRequestIdentityGuard.requirePending(request, pendingRequest);
    boolean dryRun = Boolean.TRUE.equals(request.dryRun());
    String launchPayload = JsonUtils.toJson(request);
    String tenantId = request.tenantId();
    String requestId = request.requestId();
    Long workflowDefinitionId = request.workflowDefinitionId();
    LocalDate bizDate = request.bizDate();
    WorkflowLaunchWaitInsertParam waitInsertParam = new WorkflowLaunchWaitInsertParam(
        tenantId,
        requestId,
        workflowDefinitionId,
        bizDate,
        dryRun,
        launchPayload);
    int persisted = waitingMapper.defer(waitInsertParam);
    if (persisted != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.launch_not_pending");
    }
    int updated = triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), TriggerRequestStatus.PROCESSING.code(), null);
    if (updated != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.launch_not_pending");
    }
  }

  @Transactional
  public void markResumed(LaunchRequest request) {
    waitingMapper.setTenantContext(request.tenantId());
    // 普通根启动可能从未等待；无等待或已收敛的零行是幂等无操作，不复活 REJECTED。
    waitingMapper.markResumed(request.tenantId(), request.requestId());
  }

  @Transactional
  public List<WorkflowLaunchWaitClaim> claimDue(String tenantId, int limit, Instant nextAttemptAt) {
    waitingMapper.setTenantContext(tenantId);
    return waitingMapper.claimDue(tenantId, limit, nextAttemptAt);
  }

  @Transactional
  public void reject(String tenantId, String requestId) {
    waitingMapper.setTenantContext(tenantId);
    // 与 defer 保持原请求 → 等待行的统一锁顺序。
    TriggerRequestEntity original =
        triggerRequestMapper.selectByTenantAndRequestIdForUpdate(tenantId, requestId);
    if (EmptyChecks.isNull(original)) {
      // NO ACTION要求先显式清理等待；并发清理后的重复拒绝不创建新状态。
      return;
    }
    if (TriggerRequestStatus.LAUNCHED.code().equals(original.getRequestStatus())) {
      waitingMapper.markResumed(tenantId, requestId);
      return;
    }
    int changed = waitingMapper.markRejected(tenantId, requestId);
    if (changed == 0) {
      return;
    }
    if (List.of(
            TriggerRequestStatus.REJECTED.code(),
            TriggerRequestStatus.DUPLICATE.code(),
            TriggerRequestStatus.GIVE_UP.code())
        .contains(original.getRequestStatus())) {
      return;
    }
    int updated = triggerRequestMapper.updateAcceptance(
        tenantId, requestId, TriggerRequestStatus.REJECTED.code(), null);
    if (updated != 1) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.launch_not_pending");
    }
  }
}
