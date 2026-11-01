package io.github.pinpols.batch.trigger.service;

import static io.github.pinpols.batch.trigger.service.TriggerAdmissionRules.CLOSED_BATCH_DAY_SQL_STATE;
import static io.github.pinpols.batch.trigger.service.TriggerAdmissionRules.REQUEST_BODY_REQUIRED;

import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.dto.LaunchResponse;
import io.github.pinpols.batch.common.dto.ScheduledOccurrenceCatchUpResponse;
import io.github.pinpols.batch.common.enums.CatchUpPolicyType;
import io.github.pinpols.batch.common.enums.MisfirePendingStatus;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.ScheduledOccurrenceStatus;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.enums.TriggerType;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.jdbc.JdbcExceptionSupport;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.persistence.entity.TriggerMisfirePendingEntity;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Guard;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.trigger.application.UpstreamReadinessPort;
import io.github.pinpols.batch.trigger.config.TriggerRuntimeProperties;
import io.github.pinpols.batch.trigger.domain.TriggerDefinitionLoader;
import io.github.pinpols.batch.trigger.domain.command.PendingCatchUpApprovalCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpCommand;
import io.github.pinpols.batch.trigger.domain.command.ScheduledOccurrenceCatchUpInput;
import io.github.pinpols.batch.trigger.domain.command.ScheduledTriggerCommand;
import io.github.pinpols.batch.trigger.mapper.TriggerMisfirePendingMapper;
import io.github.pinpols.batch.trigger.mapper.TriggerRequestMapper;
import io.github.pinpols.batch.trigger.support.CalendarBizDateDefinition;
import io.github.pinpols.batch.trigger.support.ScheduledOccurrenceObservation;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Quartz fire、readiness、已拒绝 occurrence 补跑和待审批快照恢复。
 *
 * <p>原有显式事务留在对应流程；createPendingCatchUp 继续由 TriggerService 门面上的事务包围，不在本协作者叠加事务。 */
@Component
@RequiredArgsConstructor
@Slf4j
final class TriggerScheduledRecoveryService {

  private final LaunchAdapterService launchAdapterService;
  private final TriggerRequestMapper triggerRequestMapper;
  private final TriggerMisfirePendingMapper triggerMisfirePendingMapper;
  private final TriggerCalendarDefinitionProvider calendarDefinitionProvider;
  private final ScheduledOccurrenceRecorder occurrenceRecorder;
  private final PlatformTransactionManager transactionManager;
  private final UpstreamReadinessPort upstreamReadiness;
  private final TriggerRuntimeProperties triggerRuntimeProperties;
  private final TriggerDefinitionLoader triggerDefinitionLoader;
  private final TriggerAdmissionRules admissionRules;
  private final TriggerRequestPersistence requestPersistence;

  private record PendingApprovalTarget(
      TriggerRequestEntity request,
      Long pendingId,
      boolean approvePending,
      Instant scheduledFireTime) {}

  ScheduledOccurrenceCatchUpResponse catchUpRejectedOccurrence(
      ScheduledOccurrenceCatchUpCommand command) {
    if (EmptyChecks.isNull(command) || EmptyChecks.isNull(command.request())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    ScheduledOccurrenceCatchUpInput request = command.request();
    boolean requiredCatchUpFieldsAreMissing = EmptyChecks.isBlank(request.tenantId())
        || EmptyChecks.isNull(request.workflowDefinitionId())
        || EmptyChecks.isNull(request.scheduledFireTime())
        || EmptyChecks.isNull(request.bizDate())
        || EmptyChecks.isBlank(request.reason())
        || EmptyChecks.isBlank(request.requestedBy())
        || EmptyChecks.isBlank(command.idempotencyKey())
        || EmptyChecks.isBlank(command.requestId())
        || EmptyChecks.isBlank(command.traceId());
    if (requiredCatchUpFieldsAreMissing) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, REQUEST_BODY_REQUIRED);
    }
    admissionRules.assertTenantActive(request.tenantId());
    String reason = request.reason().trim();
    String requestedBy = request.requestedBy().trim();
    Instant scheduledFireTime = request.scheduledFireTime();
    TriggerDescriptor descriptor = triggerDefinitionLoader.loadByWorkflowDefinitionId(
        request.tenantId(), request.workflowDefinitionId());
    if (EmptyChecks.isNull(descriptor) || !descriptor.isWorkflowEnabled()) {
      throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.definition_not_found");
    }
    CatchUpPolicyType catchUpPolicy = CatchUpPolicyType.fromCode(descriptor.getCatchUpPolicy());
    if (catchUpPolicy == CatchUpPolicyType.NONE) {
      throw BizException.of(
          ResultCode.STATE_CONFLICT, "error.trigger.occurrence_catch_up_disabled");
    }
    String dedupKey = "scheduled-occurrence-catch-up:"
        + request.workflowDefinitionId()
        + ":"
        + scheduledFireTime.toEpochMilli();
    Map<String, Object> params = new LinkedHashMap<>();
    params.put(
        "operationType",
        catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL
            ? "SCHEDULED_OCCURRENCE_CATCH_UP_APPROVAL"
            : "SCHEDULED_OCCURRENCE_CATCH_UP");
    String scheduledFireTimeText = scheduledFireTime.toString();
    params.put("scheduledFireTime", scheduledFireTimeText);
    params.put("requestedBy", requestedBy);
    params.put("reason", reason);
    LaunchRequest launchRequest = LaunchRequest.builder()
        .tenantId(request.tenantId())
        .workflowDefinitionId(request.workflowDefinitionId())
        .bizDate(request.bizDate())
        .triggerType(TriggerType.CATCH_UP)
        .requestId(command.requestId())
        .traceId(command.traceId())
        .params(params)
        .build();
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx.execute(ignored -> {
      triggerRequestMapper.acquireBatchDaySettlementFence(
          request.tenantId(), request.workflowDefinitionId(), null, request.bizDate());
      String occurrenceStatus = occurrenceRecorder.lockAndReadStatus(
          request.tenantId(), request.workflowDefinitionId(), scheduledFireTime, request.bizDate());
      if (ScheduledOccurrenceStatus.REQUESTED.code().equals(occurrenceStatus)
          || ScheduledOccurrenceStatus.MISFIRE_PENDING.code().equals(occurrenceStatus)) {
        String acceptedRequestId = occurrenceRecorder.catchUpRequestId(
            request.tenantId(), request.workflowDefinitionId(), scheduledFireTime);
        if (EmptyChecks.isNotBlank(acceptedRequestId)) {
          TriggerRequestEntity accepted = triggerRequestMapper.selectByTenantAndRequestId(
              request.tenantId(), acceptedRequestId);
          return occurrenceCatchUpResponse(accepted.getRequestId(), occurrenceStatus);
        }
      }
      if (!ScheduledOccurrenceStatus.CLOSED_DAY_REJECTED.code().equals(occurrenceStatus)) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.occurrence_not_rejected");
      }
      TriggerRequestEntity existing =
          triggerRequestMapper.selectByTenantAndDedupKey(request.tenantId(), dedupKey);
      if (EmptyChecks.isNotNull(existing)) {
        return occurrenceCatchUpResponse(existing.getRequestId(), occurrenceStatus);
      }
      TriggerRequestEntity accepted =
          requestPersistence.buildAcceptedEntity(launchRequest, dedupKey);
      triggerRequestMapper.insert(accepted);
      if (catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL) {
        TriggerMisfirePendingEntity pending = new TriggerMisfirePendingEntity();
        pending.setTenantId(request.tenantId());
        pending.setWorkflowDefinitionId(request.workflowDefinitionId());
        pending.setJobCode(descriptor.getJobCode());
        pending.setScheduledFireTime(scheduledFireTime);
        Duration pendingRetention =
            Duration.ofDays(triggerRuntimeProperties.getMisfirePendingRetentionDays());
        Instant pendingExpiresAt = BatchDateTimeSupport.utcNow().plus(pendingRetention);
        pending.setExpiresAt(pendingExpiresAt);
        if (triggerMisfirePendingMapper.insertPending(pending) == 0) {
          pending = triggerMisfirePendingMapper.selectBySchedule(
              request.tenantId(), request.workflowDefinitionId(), scheduledFireTime);
        }
        Guard.requireFound(pending, "misfire pending occurrence not found");
        triggerMisfirePendingMapper.linkCatchUpRequest(pending.getId(), accepted.getId());
      } else {
        requestPersistence.publishLaunchOutbox(launchRequest, dedupKey);
      }
      if (!occurrenceRecorder.acceptRejectedOccurrence(
          command,
          scheduledFireTime,
          requestedBy,
          reason,
          catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL
              ? ScheduledOccurrenceStatus.MISFIRE_PENDING
              : ScheduledOccurrenceStatus.REQUESTED)) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.occurrence_not_rejected");
      }
      return occurrenceCatchUpResponse(
          command.requestId(),
          catchUpPolicy == CatchUpPolicyType.MANUAL_APPROVAL
              ? ScheduledOccurrenceStatus.MISFIRE_PENDING.code()
              : ScheduledOccurrenceStatus.REQUESTED.code());
    });
  }

  private ScheduledOccurrenceCatchUpResponse occurrenceCatchUpResponse(
      String requestId, String occurrenceStatus) {
    String status = ScheduledOccurrenceStatus.MISFIRE_PENDING.code().equals(occurrenceStatus)
        ? "PENDING_APPROVAL"
        : "ACCEPTED";
    return new ScheduledOccurrenceCatchUpResponse(requestId, status);
  }

  LaunchResponse launchScheduled(ScheduledTriggerCommand command) {
    admissionRules.assertTenantActive(command.descriptor().getTenantId());
    assertScheduledWorkflowAdmitted(command);
    ScheduledOccurrenceObservation observed =
        occurrenceRecorder.observed(command.descriptor(), command.fireTime());
    LaunchResponse settled = settledOccurrenceResponse(command, observed);
    if (EmptyChecks.isNotNull(settled)) {
      return settled;
    }
    LaunchRequest launchRequest = resolveScheduledRequest(command, observed);
    if (EmptyChecks.isNull(launchRequest.bizDate())) {
      occurrenceRecorder.record(
          command.descriptor(),
          command.fireTime(),
          null,
          ScheduledOccurrenceStatus.SKIPPED_CALENDAR,
          null);
      return skipScheduled(command);
    }
    Optional<Instant> dependencyReadyAt = upstreamReadyAt(command, launchRequest);
    if (dependencyReadyAt.isPresent()) {
      String dependencyReadyAtText = dependencyReadyAt.get().toString();
      Map<String, Object> params = new LinkedHashMap<>(launchRequest.params());
      params.put("dependencyReadyAt", dependencyReadyAtText);
      launchRequest = launchRequest.toBuilder().params(params).build();
    }
    String dedupKey = buildScheduledDedupKey(command);
    try {
      return requestPersistence.persistAndForward(launchRequest, dedupKey, command);
    } catch (RuntimeException failure) {
      if (!JdbcExceptionSupport.hasSqlState(failure, CLOSED_BATCH_DAY_SQL_STATE)) {
        throw failure;
      }
      int recorded = occurrenceRecorder.recordClosedBatchDayRejection(
          command.descriptor(),
          command.fireTime(),
          launchRequest.bizDate(),
          "BATCH_DAY_CLOSED: explicit CATCH_UP or RERUN request required");
      if (recorded == 0) {
        ScheduledOccurrenceObservation latest =
            occurrenceRecorder.observed(command.descriptor(), command.fireTime());
        LaunchResponse latestResponse = settledOccurrenceResponse(command, latest);
        if (EmptyChecks.isNotNull(latestResponse)) {
          return latestResponse;
        }
      }
      String tenantIdForLog = LogSanitizer.value(command.descriptor().getTenantId());
      String workflowCodeForLog = LogSanitizer.value(command.descriptor().getWorkflowCode());
      log.warn(
          "scheduled fire rejected because its batch day is closed: tenantId={}, workflowCode={},"
              + " bizDate={}, fireTime={}",
          tenantIdForLog,
          workflowCodeForLog,
          launchRequest.bizDate(),
          command.fireTime());
      return LaunchResponse.skipped(command.traceId());
    }
  }

  private LaunchResponse settledOccurrenceResponse(
      ScheduledTriggerCommand command, ScheduledOccurrenceObservation observed) {
    if (EmptyChecks.isNull(observed)) {
      return null;
    }
    if (ScheduledOccurrenceStatus.REQUESTED.code().equals(observed.status())
        || (ScheduledOccurrenceStatus.MISFIRE_PENDING.code().equals(observed.status())
            && EmptyChecks.isNotNull(observed.requestId()))) {
      TriggerRequestEntity accepted = triggerRequestMapper.selectByTenantAndRequestId(
          command.descriptor().getTenantId(), observed.requestId());
      if (EmptyChecks.isNull(accepted)
          || !Objects.equals(
              accepted.getWorkflowDefinitionId(), command.descriptor().getWorkflowDefinitionId())
          || !Objects.equals(accepted.getBizDate(), observed.bizDate())) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.trigger_target_mismatch");
      }
      return new LaunchResponse(accepted.getRequestId(), accepted.getTraceId());
    }
    if (ScheduledOccurrenceStatus.CLOSED_DAY_REJECTED.code().equals(observed.status())
        && command.triggerType() == TriggerType.CATCH_UP) {
      // 已关闭批日保留既有显式补跑合同；普通迟到调度仍不能撤销拒绝。
      return null;
    }
    if (!ScheduledOccurrenceStatus.WAITING_READINESS.code().equals(observed.status())
        && !ScheduledOccurrenceStatus.MISFIRE_PENDING.code().equals(observed.status())) {
      // 普通回调不能撤销终态；批日拒绝后的人工补跑仍走独立审批入口。
      return LaunchResponse.skipped(command.traceId());
    }
    return null;
  }

  private LaunchRequest resolveScheduledRequest(
      ScheduledTriggerCommand command, ScheduledOccurrenceObservation observed) {
    if (EmptyChecks.isNotNull(observed)) {
      String calendarCode =
          CodeNormalizer.toConfigFormOrNull(command.descriptor().getCalendarCode());
      if (!Objects.equals(
              observed.scheduleVersion(), admissionRules.workflowVersion(command.descriptor()))
          || !Objects.equals(observed.calendarCode(), calendarCode)
          || EmptyChecks.isNull(observed.bizDate())) {
        throw BizException.of(ResultCode.STATE_CONFLICT, "error.workflow.scheduled_version_stale");
      }
      return launchAdapterService.fromObservedScheduledTrigger(command, observed.bizDate());
    }
    CalendarBizDateDefinition calendar = calendarDefinitionProvider.load(
        command.descriptor().getTenantId(), command.descriptor().getCalendarCode());
    return launchAdapterService.fromScheduledTrigger(command, calendar);
  }

  /**
   * ADR-043 依赖感知 fire 闸:所有声明的上游都必须就绪后才 fire。
   *
   * <p>无声明(绝大多数存量触发器)→ 直接放行,行为不变。
   */
  private Optional<Instant> upstreamReadyAt(
      ScheduledTriggerCommand command, LaunchRequest launchRequest) {
    List<String> dependencies = EmptyChecks.isNull(command.descriptor().getDependsOnJobCodes())
        ? List.of()
        : command.descriptor().getDependsOnJobCodes().stream()
            .filter(Texts::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    if (EmptyChecks.isNull(command.descriptor().getDependsOnJobCodes())
        && Texts.hasText(command.descriptor().getDependsOnJobCode())) {
      String dependencyJobCode = command.descriptor().getDependsOnJobCode();
      String trimmedDependencyJobCode = dependencyJobCode.trim();
      dependencies = List.of(trimmedDependencyJobCode);
    }
    if (EmptyChecks.isEmpty(dependencies)) {
      return Optional.empty();
    }
    Instant latestReadyAt = null;
    for (String dependency : dependencies) {
      Optional<Instant> readyAt =
          upstreamReadiness.readyAt(launchRequest.tenantId(), dependency, launchRequest.bizDate());
      if (!readyAt.isPresent()) {
        // ADR-043:未就绪依赖保持当前 fire 的原业务日，交由 Quartz 延迟重检。
        throw new UpstreamNotReadyException(
            launchRequest.tenantId(),
            command.descriptor().getJobCode(),
            dependency,
            launchRequest.bizDate());
      }
      if (EmptyChecks.isNull(latestReadyAt) || readyAt.get().isAfter(latestReadyAt)) {
        latestReadyAt = readyAt.get();
      }
    }
    return Optional.of(latestReadyAt);
  }

  LaunchResponse createPendingCatchUp(ScheduledTriggerCommand command) {
    admissionRules.assertTenantActive(command.descriptor().getTenantId());
    assertScheduledWorkflowAdmitted(command);
    ScheduledOccurrenceObservation observed =
        occurrenceRecorder.observed(command.descriptor(), command.fireTime());
    LaunchResponse settled = settledOccurrenceResponse(command, observed);
    if (EmptyChecks.isNotNull(settled)) {
      return settled;
    }
    LaunchRequest launchRequest = resolveScheduledRequest(command, observed);
    if (EmptyChecks.isNull(launchRequest.bizDate())) {
      occurrenceRecorder.record(
          command.descriptor(),
          command.fireTime(),
          null,
          ScheduledOccurrenceStatus.SKIPPED_CALENDAR,
          null);
      return skipScheduled(command);
    }
    String dedupKey = buildScheduledDedupKey(command);
    LaunchResponse response = persistPending(launchRequest, dedupKey, command);
    occurrenceRecorder.recordAccepted(
        command,
        launchRequest.bizDate(),
        ScheduledOccurrenceStatus.MISFIRE_PENDING,
        response.instanceNo());
    return response;
  }

  LaunchResponse approvePendingCatchUp(PendingCatchUpApprovalCommand command) {
    validatePendingApproval(command);
    admissionRules.assertTenantActive(command.getTenantId());

    PendingApprovalTarget pendingTarget = resolveRequestFromPending(command);
    TriggerRequestEntity requestFromPending =
        EmptyChecks.isNull(pendingTarget) ? null : pendingTarget.request();

    // 5.6:幂等 —— 若已有相同 key 的请求被 launch 过,则提前返回
    if (EmptyChecks.isNull(requestFromPending)
        && EmptyChecks.isNotBlank(command.getIdempotencyKey())) {
      TriggerRequestEntity existing = triggerRequestMapper.selectByTenantAndDedupKey(
          command.getTenantId(), command.getIdempotencyKey());
      if (EmptyChecks.isNotNull(existing)
          && TriggerRequestStatus.LAUNCHED.code().equalsIgnoreCase(existing.getRequestStatus())) {
        return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
      }
    }

    // ADR-010：审批通过后通过 outbox 异步转发，绝不走同步 HTTP。CAS → INSERT outbox；实际建 Run 后由 Orchestrator 标 LAUNCHED
    // 接纳与发布在同事务内完成，PROCESSING 持续到 Orchestrator 完成实际启动或持久化等待。
    // relay 周期把 trigger_outbox_event 推到 Kafka topic batch.trigger.launch.v1，
    // orchestrator 端消费触发实际 launch；最终一致性由 outbox + (tenant,request_id) 唯一约束保证。
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return tx.execute(ignored -> {
      TriggerRequestEntity pendingRequest = EmptyChecks.isNotNull(requestFromPending)
          ? triggerRequestMapper.selectById(requestFromPending.getId())
          : triggerRequestMapper.selectByTenantAndRequestId(
              command.getTenantId(), command.getRequestId());
      Guard.requireFound(pendingRequest, "pending catch-up request not found");
      if (!TriggerType.CATCH_UP.code().equalsIgnoreCase(pendingRequest.getTriggerType())) {
        throw BizException.of(ResultCode.BUSINESS_ERROR, "error.request.not_catch_up");
      }
      if (TriggerRequestStatus.REJECTED
          .code()
          .equalsIgnoreCase(pendingRequest.getRequestStatus())) {
        throw BizException.of(ResultCode.BUSINESS_ERROR, "error.request.already_rejected");
      }
      if (TriggerRequestStatus.LAUNCHED
          .code()
          .equalsIgnoreCase(pendingRequest.getRequestStatus())) {
        return new LaunchResponse(pendingRequest.getRequestId(), pendingRequest.getTraceId());
      }
      LaunchRequest original = restorePendingLaunch(pendingRequest);
      // 原子 CAS：ACCEPTED → PROCESSING，并发审批只有一个能进入
      int claimed = triggerRequestMapper.updateRequestStatusConditional(
          command.getTenantId(),
          command.getRequestId(),
          TriggerRequestStatus.PROCESSING.code(),
          TriggerRequestStatus.ACCEPTED.code());
      if (claimed <= 0) {
        // 另一实例正在处理；返回当前状态，重试方需重新查询
        return new LaunchResponse(pendingRequest.getRequestId(), pendingRequest.getTraceId());
      }
      boolean pendingTargetRequiresApproval =
          EmptyChecks.isNotNull(pendingTarget) && pendingTarget.approvePending();
      if (pendingTargetRequiresApproval) {
        int approved =
            triggerMisfirePendingMapper.approve(pendingTarget.pendingId(), "trigger-api");
        if (approved != 1) {
          TriggerMisfirePendingEntity current =
              triggerMisfirePendingMapper.selectById(pendingTarget.pendingId());
          if (EmptyChecks.isNull(current)
              || !MisfirePendingStatus.APPROVED.code().equals(current.getStatus())) {
            throw BizException.of(
                ResultCode.STATE_CONFLICT, "error.trigger.pending_approval_changed");
          }
        }
      }
      Map<String, Object> restoredParams =
          EmptyChecks.isNull(original.params()) ? Map.of() : original.params();
      Map<String, Object> approvalParams = new LinkedHashMap<>(restoredParams);
      approvalParams.put("operationType", "CATCH_UP_APPROVAL");
      approvalParams.put("approvalMode", "MANUAL_APPROVAL");
      approvalParams.put("catchUpApproved", true);
      String approvalReason = EmptyChecks.isNull(command.getReason()) ? "" : command.getReason();
      approvalParams.put("reason", approvalReason);
      LaunchRequest launchRequest = original.toBuilder().params(approvalParams).build();
      String dedupKey = Texts.hasText(pendingRequest.getDedupKey())
          ? pendingRequest.getDedupKey()
          : pendingRequest.getRequestId();
      requestPersistence.publishLaunchOutbox(launchRequest, dedupKey);
      // 进程可能在恢复状态提交后、事务提交回调触发 DAG
      // 推进前停止。先根据已持久化的成功节点运行记录协调，
      boolean pendingMisfireCanBeApproved = EmptyChecks.isNotNull(pendingTarget)
          && pendingTarget.approvePending()
          && EmptyChecks.isNotNull(pendingTarget.scheduledFireTime());
      if (pendingMisfireCanBeApproved) {
        occurrenceRecorder.transition(
            command.getTenantId(),
            pendingRequest.getWorkflowDefinitionId(),
            pendingTarget.scheduledFireTime(),
            ScheduledOccurrenceStatus.MISFIRE_PENDING,
            ScheduledOccurrenceStatus.REQUESTED,
            pendingRequest.getRequestId());
      }
      return new LaunchResponse(pendingRequest.getRequestId(), pendingRequest.getTraceId());
    });
  }

  private LaunchRequest restorePendingLaunch(TriggerRequestEntity pending) {
    LaunchRequest original;
    try {
      original = EmptyChecks.isBlank(pending.getLaunchPayload())
          ? null
          : JsonUtils.fromJsonStrict(pending.getLaunchPayload(), LaunchRequest.class);
    } catch (IllegalArgumentException invalidPayload) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.pending_snapshot_invalid");
    }
    boolean pendingLaunchSnapshotIsInconsistent = EmptyChecks.isNull(original)
        || !Objects.equals(original.tenantId(), pending.getTenantId())
        || !Objects.equals(original.requestId(), pending.getRequestId())
        || !Objects.equals(original.workflowDefinitionId(), pending.getWorkflowDefinitionId())
        || !Objects.equals(original.jobCode(), pending.getJobCode())
        || !Objects.equals(original.bizDate(), pending.getBizDate())
        || !Objects.equals(original.traceId(), pending.getTraceId())
        || TriggerType.CATCH_UP != original.triggerType()
        || Boolean.TRUE.equals(original.dryRun()) != Boolean.TRUE.equals(pending.getDryRun());
    if (pendingLaunchSnapshotIsInconsistent) {
      throw BizException.of(ResultCode.STATE_CONFLICT, "error.trigger.pending_snapshot_invalid");
    }
    return original;
  }

  /** MANUAL_APPROVAL 场景先把 catch-up 请求登记为待审批，不立即转给 orchestrator。 */
  private LaunchResponse persistPending(
      LaunchRequest launchRequest, String dedupKey, ScheduledTriggerCommand command) {
    triggerRequestMapper.acquireBatchDaySettlementFence(
        launchRequest.tenantId(),
        launchRequest.workflowDefinitionId(),
        launchRequest.jobCode(),
        launchRequest.bizDate());
    TriggerRequestEntity existing =
        triggerRequestMapper.selectByTenantAndDedupKey(launchRequest.tenantId(), dedupKey);
    if (EmptyChecks.isNotNull(existing)) {
      linkMisfirePending(command, existing);
      return new LaunchResponse(existing.getRequestId(), existing.getTraceId());
    }
    TriggerRequestEntity entity = requestPersistence.buildAcceptedEntity(
        launchRequest.toBuilder().triggerType(TriggerType.CATCH_UP).build(), dedupKey);
    triggerRequestMapper.insert(entity);
    linkMisfirePending(command, entity);
    return new LaunchResponse(entity.getRequestId(), entity.getTraceId());
  }

  private void linkMisfirePending(ScheduledTriggerCommand command, TriggerRequestEntity request) {
    if (EmptyChecks.isNull(command) || EmptyChecks.isNull(request)) {
      return;
    }
    TriggerMisfirePendingEntity pending = new TriggerMisfirePendingEntity();
    pending.setTenantId(command.descriptor().getTenantId());
    pending.setWorkflowDefinitionId(command.descriptor().getWorkflowDefinitionId());
    pending.setJobCode(command.descriptor().getJobCode());
    pending.setScheduledFireTime(command.fireTime());
    Instant now = BatchDateTimeSupport.utcNow();
    int retentionDays = triggerRuntimeProperties.getMisfirePendingRetentionDays();
    Duration retentionWindow = Duration.ofDays(retentionDays);
    pending.setExpiresAt(now.plus(retentionWindow));
    if (triggerMisfirePendingMapper.insertPending(pending) == 0) {
      pending = triggerMisfirePendingMapper.selectBySchedule(
          command.descriptor().getTenantId(),
          command.descriptor().getWorkflowDefinitionId(),
          command.fireTime());
    }
    boolean canAssociateCatchUpRequest = EmptyChecks.isNotNull(pending)
        && EmptyChecks.isNotNull(pending.getId())
        && EmptyChecks.isNotNull(request.getId());
    if (canAssociateCatchUpRequest) {
      triggerMisfirePendingMapper.linkCatchUpRequest(pending.getId(), request.getId());
    }
  }

  private PendingApprovalTarget resolveRequestFromPending(PendingCatchUpApprovalCommand command) {
    TriggerMisfirePendingEntity pending;
    if (EmptyChecks.isNull(command.getPendingId())) {
      pending = triggerMisfirePendingMapper.selectByCatchUpRequest(
          command.getTenantId(), command.getRequestId());
      // BatchDay 审批没有 Quartz 待办；有待办时必须在本次事务内完成原义务审批。
      if (EmptyChecks.isNull(pending)) {
        return null;
      }
    } else {
      Long requestedPendingId = command.getPendingId();
      TriggerMisfirePendingEntity storedPending =
          triggerMisfirePendingMapper.selectById(requestedPendingId);
      pending = Guard.requireFound(storedPending, "misfire pending not found");
    }
    if (!command.getTenantId().equals(pending.getTenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    if (MisfirePendingStatus.REJECTED.code().equalsIgnoreCase(pending.getStatus())
        || MisfirePendingStatus.EXPIRED.code().equalsIgnoreCase(pending.getStatus())) {
      throw BizException.of(ResultCode.BUSINESS_ERROR, "error.request.already_rejected");
    }
    Long requestId = pending.getCatchUpRequestId();
    if (EmptyChecks.isNull(requestId)) {
      throw BizException.of(
          ResultCode.BUSINESS_ERROR,
          ResultCode.BUSINESS_ERROR.detailKey(),
          "misfire pending is not linked to catch-up request");
    }
    TriggerRequestEntity storedRequest = triggerRequestMapper.selectById(requestId);
    TriggerRequestEntity request = Guard.requireFound(storedRequest, "catch-up request not found");
    command.setRequestId(request.getRequestId());
    boolean approvalPending =
        MisfirePendingStatus.PENDING.code().equalsIgnoreCase(pending.getStatus());
    return new PendingApprovalTarget(
        request, pending.getId(), approvalPending, pending.getScheduledFireTime());
  }

  private String buildScheduledDedupKey(ScheduledTriggerCommand command) {
    // R-arch-audit-2026-05-23 P2: 用 Instant.toEpochMilli() 替代 Instant.toString()。
    // Instant.toString() 输出会按精度自动调整（如 "...:00Z" vs "...:00.000Z"），不同 JVM /
    // 序列化路径可能产生不同字符串，导致同一 fireTime 算出不同 dedupKey，去重失效。
    // toEpochMilli() 是稳定的 long → String，跨 JVM 一致。
    String target = EmptyChecks.isNull(command.descriptor().getWorkflowDefinitionId())
        ? "job:" + command.descriptor().getJobCode()
        : "workflow:" + command.descriptor().getWorkflowDefinitionId();
    return command.descriptor().getTenantId()
        + ":"
        + target
        + ":"
        + command.fireTime().toEpochMilli();
  }

  private LaunchResponse skipScheduled(ScheduledTriggerCommand command) {
    String tenantIdForLog = LogSanitizer.value(command.descriptor().getTenantId());
    String jobCodeForLog = LogSanitizer.value(command.descriptor().getJobCode());
    String calendarCodeForLog = LogSanitizer.value(command.descriptor().getCalendarCode());
    log.info(
        "scheduled trigger skipped by business calendar: tenantId={}, jobCode={},"
            + " calendarCode={}, fireTime={}",
        tenantIdForLog,
        jobCodeForLog,
        calendarCodeForLog,
        command.fireTime());
    return LaunchResponse.skipped(command.traceId());
  }

  private void assertScheduledWorkflowAdmitted(ScheduledTriggerCommand command) {
    if (triggerRequestMapper.existsEnabledWorkflowDefinition(
        command.descriptor().getTenantId(), command.descriptor().getWorkflowDefinitionId())) {
      return;
    }
    // 再判断过期根运行是否应进入终态；派发守卫会安全处理已活动节点。
    boolean pendingMisfireRecoveryIsBeingHandled = command.triggerType() == TriggerType.CATCH_UP
        && occurrenceRecorder.isPendingMisfireRecovery(command.descriptor(), command.fireTime());
    if (pendingMisfireRecoveryIsBeingHandled) {
      return;
    }
    throw BizException.of(ResultCode.NOT_FOUND, "error.workflow.definition_not_found");
  }

  private void validatePendingApproval(PendingCatchUpApprovalCommand command) {
    Guard.require(EmptyChecks.isNotNull(command), "approval command is required");
    if (EmptyChecks.isBlank(command.getTenantId())) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.tenant_id_required");
    }
    boolean approvalTargetIsMissing =
        EmptyChecks.isNull(command.getPendingId()) && EmptyChecks.isBlank(command.getRequestId());
    if (approvalTargetIsMissing) {
      throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.common.request_id_required");
    }
  }
}
