package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.DependencyResultReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.persistence.entity.TriggerRequestEntity;
import io.github.pinpols.batch.common.rls.RlsTenantContextHolder;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionService;
import io.github.pinpols.batch.orchestrator.application.service.asset.AssetPartitionSnapshot;
import io.github.pinpols.batch.orchestrator.application.service.workflow.BizDateArithmetic;
import io.github.pinpols.batch.orchestrator.domain.entity.BusinessCalendarEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.JobDependencyWaitingLaunchEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyRule;
import io.github.pinpols.batch.orchestrator.domain.query.JobDependencyWaitingLaunchSummary;
import io.github.pinpols.batch.orchestrator.mapper.BatchDayPlanCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.BusinessCalendarMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyMapper;
import io.github.pinpols.batch.orchestrator.mapper.JobDependencyWaitingLaunchMapper;
import io.github.pinpols.batch.orchestrator.mapper.TriggerRequestMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 保留启动请求，直到每条已声明上游依赖边都具备日期对齐的生效结果。 */
@Service
@RequiredArgsConstructor
public class JobDependencyWaitingLaunchService {

  private static final String INVALID_DEPENDENCY_SNAPSHOT = "DEPENDENCY_SNAPSHOT_INVALID";
  private static final Set<String> DEPENDENCY_SNAPSHOT_FIELDS =
      Set.of("upstreamJobCode", "bizDateAlignment", "upstreamBizDate", "resolutionError");

  private record DependencySnapshot(
      String upstreamJobCode,
      String bizDateAlignment,
      LocalDate upstreamBizDate,
      String resolutionError) {}

  private final JobDependencyMapper dependencyMapper;
  private final JobDependencyWaitingLaunchMapper waitingMapper;
  private final BizDateArithmetic bizDateArithmetic;
  private final AssetPartitionService assetPartitionService;
  private final TriggerRequestMapper triggerRequestMapper;
  private final BatchDayPlanCalendarMapper planCalendarMapper;
  private final BusinessCalendarMapper businessCalendarMapper;
  private final BatchDateTimeSupport dateTimeSupport;
  private final PlatformTransactionManager transactionManager;

  public List<JobDependencyWaitingLaunchSummary> listRecent(
      String tenantId, String waitStatus, String jobCode, int limit) {
    if (EmptyChecks.isBlank(tenantId)) {
      throw new IllegalArgumentException("tenantId is required");
    }
    String status;
    if (EmptyChecks.isBlank(waitStatus)) {
      status = null;
    } else {
      String trimmedStatus = waitStatus.trim();
      status = trimmedStatus.toUpperCase(Locale.ROOT);
    }
    if (EmptyChecks.isNotNull(status)
        && !List.of("WAITING", "RELEASED", "CANCELLED").contains(status)) {
      throw new IllegalArgumentException("Unsupported job dependency wait status");
    }
    String normalizedJobCode = EmptyChecks.isBlank(jobCode) ? null : jobCode.trim();
    int pageSize = Math.max(1, Math.min(limit, 200));
    return RlsTenantContextHolder.runWithTenant(tenantId, () -> {
      TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
      return transactionTemplate.execute(tx -> {
        waitingMapper.setTenantContext(tenantId);
        return waitingMapper.selectRecent(tenantId, status, normalizedJobCode, pageSize);
      });
    });
  }

  @Transactional
  public List<JobDependencyWaitingLaunchEntity> selectWaiting(String tenantId, int limit) {
    waitingMapper.setTenantContext(tenantId);
    return waitingMapper.selectWaiting(tenantId, limit);
  }

  @Transactional(readOnly = true)
  public List<String> selectRejectedForWorkflowProgression(String tenantId, int limit) {
    waitingMapper.setTenantContext(tenantId);
    return waitingMapper.selectRejectedForWorkflowProgression(tenantId, limit);
  }

  @Transactional(readOnly = true)
  public TriggerRequestEntity selectOriginalRequest(String tenantId, String requestId) {
    waitingMapper.setTenantContext(tenantId);
    return triggerRequestMapper.selectByTenantAndRequestId(tenantId, requestId);
  }

  @Transactional
  public int markWaitCancelled(String tenantId, String requestId) {
    waitingMapper.setTenantContext(tenantId);
    return waitingMapper.markCancelled(tenantId, requestId);
  }

  @Transactional
  public int markWaitReleased(String tenantId, String requestId) {
    waitingMapper.setTenantContext(tenantId);
    return waitingMapper.markReleased(tenantId, requestId);
  }

  /** 恢复前同时绑定等待行与原请求；仅解析持久载荷，不执行数据库或外部副作用。 */
  public Optional<LaunchRequest> restoreWaitRequest(
      JobDependencyWaitingLaunchEntity waiting, TriggerRequestEntity original) {
    if (EmptyChecks.isNull(waiting) || EmptyChecks.isNull(original)) {
      return Optional.empty();
    }
    try {
      LaunchRequest request = JsonUtils.fromJson(waiting.launchPayload(), LaunchRequest.class);
      boolean waitRequestMatchesOriginal = LaunchRequestIdentityGuard.matches(request, original)
          && Objects.equals(waiting.tenantId(), request.tenantId())
          && Objects.equals(waiting.requestId(), request.requestId())
          && !EmptyChecks.isBlank(waiting.jobCode())
          && Objects.equals(waiting.jobCode(), request.jobCode())
          && Objects.equals(waiting.bizDate(), request.bizDate());
      if (!waitRequestMatchesOriginal) {
        return Optional.empty();
      }
      return Optional.of(request);
    } catch (IllegalArgumentException invalidPayload) {
      return Optional.empty();
    }
  }

  /** 原请求与等待同时拒绝；顺序固定为原请求锁→等待锁，重读后不处理过期扫描结果。 */
  @Transactional
  public void rejectInvalidWait(JobDependencyWaitingLaunchEntity observed) {
    String tenantId = observed.tenantId();
    String requestId = observed.requestId();
    waitingMapper.setTenantContext(tenantId);
    TriggerRequestEntity original =
        triggerRequestMapper.selectByTenantAndRequestIdForUpdate(tenantId, requestId);
    JobDependencyWaitingLaunchEntity current =
        waitingMapper.selectByTenantAndRequestIdForUpdate(tenantId, requestId);
    boolean observedWaitIsStillCurrent = EmptyChecks.isNotNull(current)
        && "WAITING".equals(current.waitStatus())
        && Objects.equals(observed.id(), current.id())
        && Objects.equals(observed.jobCode(), current.jobCode())
        && Objects.equals(observed.bizDate(), current.bizDate())
        && Objects.equals(observed.launchPayload(), current.launchPayload());
    if (!observedWaitIsStillCurrent) {
      return;
    }
    if (EmptyChecks.isNull(original)) {
      requireWaitWrite(waitingMapper.markCancelled(tenantId, requestId));
      return;
    }
    String status = original.getRequestStatus();
    if (TriggerRequestStatus.LAUNCHED.code().equals(status)) {
      requireWaitWrite(waitingMapper.markReleased(tenantId, requestId));
      return;
    }
    if (List.of(
            TriggerRequestStatus.REJECTED.code(),
            TriggerRequestStatus.DUPLICATE.code(),
            TriggerRequestStatus.GIVE_UP.code())
        .contains(status)) {
      requireWaitWrite(waitingMapper.markCancelled(tenantId, requestId));
      return;
    }
    if (restoreWaitRequest(current, original).isPresent()) {
      // 扫描后身份已被合法恢复，下一轮使用新事实继续处理。
      return;
    }
    if (waitingMapper.hasJobInstanceForRequest(tenantId, original.getId())) {
      requireWaitWrite(waitingMapper.recordCheck(tenantId, requestId, "LAUNCH_ALREADY_PREPARED"));
      return;
    }
    requireWaitWrite(waitingMapper.recordCheck(tenantId, requestId, "LAUNCH_PAYLOAD_INVALID"));
    requireWaitWrite(waitingMapper.markCancelled(tenantId, requestId));
    int updatedRows = triggerRequestMapper.updateAcceptance(
        tenantId, requestId, TriggerRequestStatus.REJECTED.code(), null);
    requireWaitWrite(updatedRows);
  }

  @Transactional
  public int recordWaitCheck(String tenantId, String requestId, String reason) {
    waitingMapper.setTenantContext(tenantId);
    return waitingMapper.recordCheck(tenantId, requestId, reason);
  }

  @Transactional
  public int claimWaitTimeoutAlert(String tenantId, String requestId, Instant alertedAt) {
    waitingMapper.setTenantContext(tenantId);
    return waitingMapper.claimTimeoutAlert(tenantId, requestId, alertedAt);
  }

  /**
   * @return true 表示启动必须继续延迟；若没有依赖或所有上游均已就绪，则返回 false
   */
  @Transactional
  public boolean deferIfNotReady(LaunchRequest request) {
    return !captureOrDefer(request).isPresent();
  }

  /** 在一次门禁检查中评估就绪状态，并捕获准确的有效上游版本。 */
  @Transactional
  public Optional<List<DependencyResultReference>> captureOrDefer(LaunchRequest request) {
    String contextTenantId = request.tenantId();
    waitingMapper.setTenantContext(contextTenantId);
    String pendingTenantId = request.tenantId();
    String pendingRequestId = request.requestId();
    TriggerRequestEntity pendingRequest =
        triggerRequestMapper.selectByTenantAndRequestIdForUpdate(pendingTenantId, pendingRequestId);
    LaunchRequestIdentityGuard.requirePending(request, pendingRequest);
    String existingTenantId = request.tenantId();
    String existingRequestId = request.requestId();
    JobDependencyWaitingLaunchEntity existing =
        waitingMapper.selectByTenantAndRequestId(existingTenantId, existingRequestId);
    boolean existingWaitConflictsWithRequest = EmptyChecks.isNotNull(existing)
        && (!Objects.equals(existing.tenantId(), request.tenantId())
            || !Objects.equals(existing.requestId(), request.requestId())
            || !Objects.equals(existing.jobCode(), request.jobCode())
            || !Objects.equals(existing.bizDate(), request.bizDate())
            || !"WAITING".equals(existing.waitStatus()));
    if (existingWaitConflictsWithRequest) {
      throw waitConflict();
    }
    List<DependencySnapshot> dependencies;
    if (EmptyChecks.isNull(existing)) {
      String tenantId = request.tenantId();
      String jobCode = request.jobCode();
      LocalDate businessDate = request.bizDate();
      String ruleTenantId = request.tenantId();
      String ruleJobCode = request.jobCode();
      List<JobDependencyRule> upstreamRules =
          dependencyMapper.selectUpstreamRules(ruleTenantId, ruleJobCode);
      dependencies = snapshotDependencies(tenantId, jobCode, businessDate, upstreamRules);
    } else {
      String dependencySnapshot = existing.dependencySnapshot();
      LocalDate businessDate = request.bizDate();
      dependencies = decodeDependencies(dependencySnapshot, businessDate);
    }
    List<DependencyResultReference> references = new ArrayList<>(dependencies.size());
    // 等待行只能由非空依赖建立；损坏快照不能退化为「无依赖」并放行新实例。
    String notReadyReason;
    if (EmptyChecks.isNotNull(existing) && EmptyChecks.isEmpty(dependencies)) {
      notReadyReason = INVALID_DEPENDENCY_SNAPSHOT;
    } else {
      String tenantId = request.tenantId();
      LocalDate businessDate = request.bizDate();
      notReadyReason = captureDependencyResults(tenantId, businessDate, dependencies, references);
    }
    if (EmptyChecks.isNull(notReadyReason)) {
      return Optional.of(List.copyOf(references));
    }
    if (EmptyChecks.isNull(existing)) {
      persistInitialWait(request, dependencies);
    }
    String checkTenantId = request.tenantId();
    String checkRequestId = request.requestId();
    int recordedCheck = waitingMapper.recordCheck(checkTenantId, checkRequestId, notReadyReason);
    requireWaitWrite(recordedCheck);
    String acceptanceTenantId = request.tenantId();
    String acceptanceRequestId = request.requestId();
    String waitingStatus = TriggerRequestStatus.WAITING.code();
    int updated = triggerRequestMapper.updateAcceptance(
        acceptanceTenantId, acceptanceRequestId, waitingStatus, null);
    requireWaitWrite(updated);
    return Optional.empty();
  }

  private void persistInitialWait(LaunchRequest request, List<DependencySnapshot> dependencies) {
    Instant now = dateTimeSupport.nowInstant();
    JobDependencyWaitingLaunchEntity.JobDependencyWaitingLaunchEntityBuilder builder =
        JobDependencyWaitingLaunchEntity.builder();
    builder.tenantId(request.tenantId());
    builder.requestId(request.requestId());
    builder.jobCode(request.jobCode());
    builder.bizDate(request.bizDate());
    String dependencySnapshot = encodeDependencies(dependencies);
    builder.dependencySnapshot(dependencySnapshot);
    String launchPayload = JsonUtils.toJson(request);
    builder.launchPayload(launchPayload);
    builder.waitStatus("WAITING");
    builder.attemptCount(0);
    builder.createdAt(now);
    builder.updatedAt(now);
    JobDependencyWaitingLaunchEntity waitingLaunch = builder.build();
    int inserted = waitingMapper.insertIfAbsent(waitingLaunch);
    requireWaitWrite(inserted);
  }

  private static void requireWaitWrite(int rows) {
    if (rows != 1) {
      throw waitConflict();
    }
  }

  private static BizException waitConflict() {
    return BizException.of(
        ResultCode.STATE_CONFLICT,
        ResultCode.STATE_CONFLICT.detailKey(),
        "Job dependency wait identity or state changed during admission");
  }

  private String captureDependencyResults(
      String tenantId,
      LocalDate downstreamBizDate,
      List<DependencySnapshot> dependencies,
      List<DependencyResultReference> references) {
    for (DependencySnapshot dependency : dependencies) {
      if (EmptyChecks.isNotNull(dependency.resolutionError())) {
        return dependency.resolutionError();
      }
      LocalDate upstreamBizDate = EmptyChecks.isNull(dependency.upstreamBizDate())
          ? resolveUpstreamBizDate(downstreamBizDate, dependency.bizDateAlignment())
          : dependency.upstreamBizDate();
      Optional<AssetPartitionSnapshot> partition = assetPartitionService.findEffectiveJobPartition(
          tenantId, dependency.upstreamJobCode(), upstreamBizDate);
      if (!partition.isPresent()) {
        String reason = "UPSTREAM_NOT_EFFECTIVE:" + dependency.upstreamJobCode();
        return JobDependencyRule.SAME_DAY.equals(dependency.bizDateAlignment())
            ? reason
            : reason + "@" + upstreamBizDate;
      }
      AssetPartitionSnapshot effective = partition.get();
      references.add(new DependencyResultReference(
          dependency.upstreamJobCode(),
          dependency.bizDateAlignment(),
          upstreamBizDate,
          effective.businessKey(),
          effective.versionNo(),
          effective.resultVersionId(),
          effective.jobInstanceId(),
          effective.effectiveAt()));
    }
    return null;
  }

  public boolean allDependenciesReady(
      String tenantId, String jobCode, LocalDate bizDate, String dependencySnapshot) {
    String notReadyReason = findNotReadyReason(tenantId, jobCode, bizDate, dependencySnapshot);
    return EmptyChecks.isNull(notReadyReason);
  }

  public String findNotReadyReason(
      String tenantId, String jobCode, LocalDate bizDate, List<JobDependencyRule> dependencies) {
    List<DependencySnapshot> dependencySnapshots =
        snapshotDependencies(tenantId, jobCode, bizDate, dependencies);
    return evaluateDependencies(tenantId, jobCode, bizDate, dependencySnapshots);
  }

  public String findNotReadyReason(
      String tenantId, String jobCode, LocalDate bizDate, String dependencySnapshot) {
    List<DependencySnapshot> dependencies = decodeDependencies(dependencySnapshot, bizDate);
    return EmptyChecks.isEmpty(dependencies)
        ? INVALID_DEPENDENCY_SNAPSHOT
        : evaluateDependencies(tenantId, jobCode, bizDate, dependencies);
  }

  private String evaluateDependencies(
      String tenantId, String jobCode, LocalDate bizDate, List<DependencySnapshot> dependencies) {
    if (EmptyChecks.isEmpty(dependencies)) {
      return null;
    }
    for (DependencySnapshot dependency : dependencies) {
      if (EmptyChecks.isNotNull(dependency.resolutionError())) {
        return dependency.resolutionError();
      }
      LocalDate upstreamBizDate = EmptyChecks.isNull(dependency.upstreamBizDate())
          ? resolveUpstreamBizDate(bizDate, dependency.bizDateAlignment())
          : dependency.upstreamBizDate();
      if (!assetPartitionService.isJobPartitionReady(
          tenantId, dependency.upstreamJobCode(), upstreamBizDate)) {
        String reason = "UPSTREAM_NOT_EFFECTIVE:" + dependency.upstreamJobCode();
        return JobDependencyRule.SAME_DAY.equals(dependency.bizDateAlignment())
            ? reason
            : reason + "@" + upstreamBizDate;
      }
    }
    return null;
  }

  private List<DependencySnapshot> snapshotDependencies(
      String tenantId,
      String downstreamJobCode,
      LocalDate downstreamBizDate,
      List<JobDependencyRule> rules) {
    if (EmptyChecks.isEmpty(rules)) {
      return List.of();
    }
    boolean needsPreviousBusinessDay = rules.stream()
        .anyMatch(rule -> JobDependencyRule.PREVIOUS_BIZ_DAY.equals(rule.bizDateAlignment()));
    String calendarCode = needsPreviousBusinessDay
        ? dependencyMapper.selectCalendarCodeByTenantAndJob(tenantId, downstreamJobCode)
        : null;
    BusinessCalendarEntity calendar = EmptyChecks.isBlank(calendarCode)
        ? null
        : businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true);
    boolean missingCalendar = EmptyChecks.isNotBlank(calendarCode) && EmptyChecks.isNull(calendar);
    return rules.stream()
        .map(rule -> {
          if (!JobDependencyRule.PREVIOUS_BIZ_DAY.equals(rule.bizDateAlignment())) {
            return new DependencySnapshot(
                rule.upstreamJobCode(), rule.bizDateAlignment(), downstreamBizDate, null);
          }
          if (missingCalendar) {
            return new DependencySnapshot(
                rule.upstreamJobCode(),
                rule.bizDateAlignment(),
                null,
                "DEPENDENCY_CALENDAR_UNAVAILABLE:" + calendarCode);
          }
          LocalDate previousDate = EmptyChecks.isNull(calendar)
              ? bizDateArithmetic.previousBusinessDay(downstreamBizDate)
              : planCalendarMapper.selectPreviousBusinessDate(
                  tenantId, calendarCode, downstreamBizDate);
          return EmptyChecks.isNull(previousDate)
              ? new DependencySnapshot(
                  rule.upstreamJobCode(),
                  rule.bizDateAlignment(),
                  null,
                  "DEPENDENCY_CALENDAR_HAS_NO_PRIOR_WORKDAY:" + calendarCode)
              : new DependencySnapshot(
                  rule.upstreamJobCode(), rule.bizDateAlignment(), previousDate, null);
        })
        .toList();
  }

  private String encodeDependencies(List<DependencySnapshot> dependencies) {
    List<Map<String, Object>> values = dependencies.stream()
        .map(dependency -> {
          Map<String, Object> value = new LinkedHashMap<>();
          value.put("upstreamJobCode", dependency.upstreamJobCode());
          value.put("bizDateAlignment", dependency.bizDateAlignment());
          if (JobDependencyRule.PREVIOUS_BIZ_DAY.equals(dependency.bizDateAlignment())) {
            value.put(
                "upstreamBizDate",
                EmptyChecks.isNull(dependency.upstreamBizDate())
                    ? null
                    : dependency.upstreamBizDate().toString());
            value.put("resolutionError", dependency.resolutionError());
          }
          return value;
        })
        .toList();
    return JsonUtils.toJson(values);
  }

  private List<DependencySnapshot> decodeDependencies(String json, LocalDate downstreamBizDate) {
    if (EmptyChecks.isBlank(json) || EmptyChecks.isNull(downstreamBizDate)) {
      return List.of();
    }
    try {
      List<?> rawDependencies = JsonUtils.fromJson(json, List.class);
      if (EmptyChecks.isEmpty(rawDependencies)) {
        return List.of();
      }
      return rawDependencies.stream()
          .map(raw -> decodeDependency(raw, downstreamBizDate))
          .toList();
    } catch (IllegalArgumentException | DateTimeParseException malformedSnapshot) {
      // 整批拒绝并保留原等待取证；一条损坏记录也不能中断同租户其它等待的恢复扫描。
      return List.of();
    }
  }

  private DependencySnapshot decodeDependency(Object raw, LocalDate downstreamBizDate) {
    if (!(raw instanceof Map<?, ?> values)
        || !DEPENDENCY_SNAPSHOT_FIELDS.containsAll(values.keySet())) {
      throw new IllegalArgumentException(INVALID_DEPENDENCY_SNAPSHOT);
    }
    if (!(values.get("upstreamJobCode") instanceof String upstreamJobCode)
        || EmptyChecks.isBlank(upstreamJobCode)
        || !(values.get("bizDateAlignment") instanceof String alignment)
        || !Set.of(JobDependencyRule.SAME_DAY, JobDependencyRule.PREVIOUS_BIZ_DAY)
            .contains(alignment)) {
      throw new IllegalArgumentException(INVALID_DEPENDENCY_SNAPSHOT);
    }
    Object rawError = values.get("resolutionError");
    String resolutionError = rawError instanceof String text ? text : null;
    boolean legacyResolutionErrorNeedsDecoding =
        EmptyChecks.isNotNull(rawError) && (EmptyChecks.isBlank(resolutionError));
    if (legacyResolutionErrorNeedsDecoding) {
      throw new IllegalArgumentException(INVALID_DEPENDENCY_SNAPSHOT);
    }
    Object rawDate = values.get("upstreamBizDate");
    if (JobDependencyRule.SAME_DAY.equals(alignment)) {
      boolean dependencyResolutionDoesNotMatchBusinessDate = EmptyChecks.isNotNull(resolutionError)
          || (EmptyChecks.isNotNull(rawDate)
              && !downstreamBizDate.equals(decodeUpstreamDate(rawDate)));
      if (dependencyResolutionDoesNotMatchBusinessDate) {
        throw new IllegalArgumentException(INVALID_DEPENDENCY_SNAPSHOT);
      }
      return new DependencySnapshot(upstreamJobCode, alignment, downstreamBizDate, null);
    }
    if (EmptyChecks.isNotNull(resolutionError)) {
      if (EmptyChecks.isNotNull(rawDate)) {
        throw new IllegalArgumentException(INVALID_DEPENDENCY_SNAPSHOT);
      }
      return new DependencySnapshot(upstreamJobCode, alignment, null, resolutionError);
    }
    LocalDate upstreamBizDate = decodeUpstreamDate(rawDate);
    if (!upstreamBizDate.isBefore(downstreamBizDate)) {
      throw new IllegalArgumentException(INVALID_DEPENDENCY_SNAPSHOT);
    }
    return new DependencySnapshot(upstreamJobCode, alignment, upstreamBizDate, null);
  }

  private LocalDate decodeUpstreamDate(Object value) {
    if (!(value instanceof String date) || EmptyChecks.isBlank(date)) {
      throw new IllegalArgumentException(INVALID_DEPENDENCY_SNAPSHOT);
    }
    return LocalDate.parse(date);
  }

  private LocalDate resolveUpstreamBizDate(LocalDate downstreamBizDate, String alignment) {
    if (JobDependencyRule.PREVIOUS_BIZ_DAY.equals(alignment)) {
      return bizDateArithmetic.previousBusinessDay(downstreamBizDate);
    }
    return downstreamBizDate;
  }
}
