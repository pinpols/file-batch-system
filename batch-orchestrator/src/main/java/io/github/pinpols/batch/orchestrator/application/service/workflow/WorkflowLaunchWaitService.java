package io.github.pinpols.batch.orchestrator.application.service.workflow;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.service.SecretPayloadProtector;
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
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 确认启动消息前先持久化准入名额等待；首次请求载荷优先。 */
@Service
@RequiredArgsConstructor
public class WorkflowLaunchWaitService {
  private final WorkflowLaunchWaitMapper waitingMapper;
  private final TriggerRequestMapper triggerRequestMapper;
  private final SecretPayloadProtector secretPayloadProtector;
  private static final String FORMAT = "WORKFLOW_CAPACITY_WAIT_V1";

  private record Envelope(String tenantId, String requestId, Long workflowDefinitionId,
      LocalDate bizDate, boolean dryRun, String format, String sealedSnapshotPayload) {}

  @Transactional
  public void defer(LaunchRequest request, WorkflowLaunchWaitSnapshot snapshot) {
    waitingMapper.setTenantContext(request.tenantId());
    String lookupTenantId = request.tenantId();
    String lookupRequestId = request.requestId();
    TriggerRequestEntity pendingRequest =
        triggerRequestMapper.selectByTenantAndRequestIdForUpdate(lookupTenantId, lookupRequestId);
    LaunchRequestIdentityGuard.requirePending(request, pendingRequest);
    snapshot.requireSameRequest(request);
    snapshot.validate();
    WorkflowLaunchWaitClaim previous = waitingMapper.selectWaitingForUpdate(
        request.tenantId(), request.requestId());
    boolean dryRun = Boolean.TRUE.equals(request.dryRun());
    String launchPayload;
    if (EmptyChecks.isNotNull(previous)) {
      WorkflowLaunchWaitSnapshot first = restore(previous);
      first.requireSameRequest(request);
      launchPayload = previous.launchPayload();
    } else {
      String snapshotJson = JsonUtils.toJson(snapshot);
      String sealedSnapshot = secretPayloadProtector.protect(snapshotJson);
      Envelope envelope = new Envelope(request.tenantId(), request.requestId(),
          request.workflowDefinitionId(), request.bizDate(), dryRun, FORMAT, sealedSnapshot);
      launchPayload = JsonUtils.toJson(envelope);
    }
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

  /** 原请求先锁，再读取同一等待；迟到重投不能替换首次冻结输入。 */
  @Transactional
  public WorkflowLaunchWaitSnapshot findSnapshot(LaunchRequest request) {
    waitingMapper.setTenantContext(request.tenantId());
    TriggerRequestEntity original = triggerRequestMapper.selectByTenantAndRequestIdForUpdate(
        request.tenantId(), request.requestId());
    LaunchRequestIdentityGuard.requirePending(request, original);
    WorkflowLaunchWaitClaim waiting = waitingMapper.selectWaitingForUpdate(
        request.tenantId(), request.requestId());
    if (EmptyChecks.isNull(waiting)) {
      return null;
    }
    WorkflowLaunchWaitSnapshot snapshot = restore(waiting);
    snapshot.requireSameRequest(request);
    return snapshot;
  }

  public LaunchRequest restoreRequest(String tenantId, WorkflowLaunchWaitClaim claim) {
    if (!Objects.equals(tenantId, claim.tenantId())) {
      throw WorkflowLaunchWaitSnapshot.invalidSnapshot();
    }
    return restore(claim).originalRequest();
  }

  private WorkflowLaunchWaitSnapshot restore(WorkflowLaunchWaitClaim claim) {
    try {
      Envelope envelope = JsonUtils.fromJsonStrict(claim.launchPayload(), Envelope.class);
      if (EmptyChecks.isNull(envelope) || !FORMAT.equals(envelope.format())
          || !Objects.equals(claim.tenantId(), envelope.tenantId())
          || !Objects.equals(claim.requestId(), envelope.requestId())
          || !Objects.equals(claim.workflowDefinitionId(), envelope.workflowDefinitionId())
          || !Objects.equals(claim.bizDate(), envelope.bizDate())
          || Boolean.TRUE.equals(claim.dryRun()) != envelope.dryRun()) {
        throw WorkflowLaunchWaitSnapshot.invalidSnapshot();
      }
      String plaintext = secretPayloadProtector.revealEncrypted(envelope.sealedSnapshotPayload());
      WorkflowLaunchWaitSnapshot snapshot = JsonUtils.fromJsonStrict(plaintext, WorkflowLaunchWaitSnapshot.class);
      if (EmptyChecks.isNull(snapshot)) {
        throw WorkflowLaunchWaitSnapshot.invalidSnapshot();
      }
      snapshot.validate();
      LaunchRequest original = snapshot.originalRequest();
      if (!Objects.equals(original.tenantId(), claim.tenantId())
          || !Objects.equals(original.requestId(), claim.requestId())
          || !Objects.equals(original.workflowDefinitionId(), claim.workflowDefinitionId())
          || !Objects.equals(original.bizDate(), claim.bizDate())
          || Boolean.TRUE.equals(original.dryRun()) != Boolean.TRUE.equals(claim.dryRun())) {
        throw WorkflowLaunchWaitSnapshot.invalidSnapshot();
      }
      return snapshot;
    } catch (IllegalArgumentException invalid) {
      // 不传播反序列化的原始载荷或参数；KMS运行故障仍交给调用方重试。
      throw WorkflowLaunchWaitSnapshot.invalidSnapshot();
    }
  }

  @Transactional
  public void markResumed(LaunchRequest request) {
    waitingMapper.setTenantContext(request.tenantId());
    TriggerRequestEntity original = triggerRequestMapper.selectByTenantAndRequestIdForUpdate(
        request.tenantId(), request.requestId());
    if (EmptyChecks.isNull(original)
        || !TriggerRequestStatus.LAUNCHED.code().equals(original.getRequestStatus())) {
      return;
    }
    // 与拒绝相同的原请求 → 等待行锁顺序；已收敛的零行不能复活 REJECTED。
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
