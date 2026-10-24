package io.github.pinpols.batch.orchestrator.service;

import io.github.pinpols.batch.common.dto.DependencyResultReference;
import io.github.pinpols.batch.common.dto.LaunchRequest;
import io.github.pinpols.batch.common.enums.TriggerRequestStatus;
import io.github.pinpols.batch.common.logging.LogSanitizer;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Holds launch requests until every declared upstream edge has an effective aligned-date result. */
@Slf4j
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
    if (EmptyChecks.isNull(tenantId) || EmptyChecks.isBlank(tenantId)) {
      throw new IllegalArgumentException("tenantId is required");
    }
    String status = EmptyChecks.isNull(waitStatus) || EmptyChecks.isBlank(waitStatus)
        ? null
        : waitStatus.trim().toUpperCase(Locale.ROOT);
    if (EmptyChecks.isNotNull(status)
        && !List.of("WAITING", "RELEASED", "CANCELLED").contains(status)) {
      throw new IllegalArgumentException("Unsupported job dependency wait status");
    }
    String normalizedJobCode =
        EmptyChecks.isNull(jobCode) || EmptyChecks.isBlank(jobCode) ? null : jobCode.trim();
    int pageSize = Math.max(1, Math.min(limit, 200));
    return RlsTenantContextHolder.runWithTenant(
        tenantId,
        () -> new TransactionTemplate(transactionManager)
            .execute(
                tx -> waitingMapper.selectRecent(tenantId, status, normalizedJobCode, pageSize)));
  }

  /**
   * @return true when launch must remain deferred; false when there is no dependency or all are ready
   */
  public boolean deferIfNotReady(LaunchRequest request) {
    return !captureOrDefer(request).isPresent();
  }

  /** Evaluates readiness and captures the exact effective upstream versions in one gate pass. */
  public Optional<List<DependencyResultReference>> captureOrDefer(LaunchRequest request) {
    JobDependencyWaitingLaunchEntity existing =
        waitingMapper.selectByTenantAndRequestId(request.tenantId(), request.requestId());
    List<DependencySnapshot> dependencies = EmptyChecks.isNull(existing)
        ? snapshotDependencies(
            request.tenantId(),
            request.jobCode(),
            request.bizDate(),
            dependencyMapper.selectUpstreamRules(request.tenantId(), request.jobCode()))
        : decodeDependencies(existing.dependencySnapshot(), request.bizDate());
    List<DependencyResultReference> references = new ArrayList<>(dependencies.size());
    // 等待行只能由非空依赖建立；损坏快照不能退化为「无依赖」并放行新实例。
    String notReadyReason = EmptyChecks.isNotNull(existing) && EmptyChecks.isEmpty(dependencies)
        ? INVALID_DEPENDENCY_SNAPSHOT
        : captureDependencyResults(request.tenantId(), request.bizDate(), dependencies, references);
    if (EmptyChecks.isNull(notReadyReason)) {
      return Optional.of(List.copyOf(references));
    }
    if (EmptyChecks.isNull(existing)) {
      Instant now = dateTimeSupport.nowInstant();
      waitingMapper.insertIfAbsent(JobDependencyWaitingLaunchEntity.builder()
          .tenantId(request.tenantId())
          .requestId(request.requestId())
          .jobCode(request.jobCode())
          .bizDate(request.bizDate())
          .dependencySnapshot(encodeDependencies(dependencies))
          .launchPayload(JsonUtils.toJson(request))
          .waitStatus("WAITING")
          .attemptCount(0)
          .createdAt(now)
          .updatedAt(now)
          .build());
    }
    waitingMapper.recordCheck(request.tenantId(), request.requestId(), notReadyReason);
    int updated = triggerRequestMapper.updateAcceptance(
        request.tenantId(), request.requestId(), TriggerRequestStatus.WAITING.code(), null);
    if (updated == 0) {
      log.warn(
          "dependency wait left trigger request unchanged: tenantId={} requestId={}",
          LogSanitizer.value(request.tenantId()),
          LogSanitizer.value(request.requestId()));
    }
    return Optional.empty();
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
    return EmptyChecks.isNull(findNotReadyReason(tenantId, jobCode, bizDate, dependencySnapshot));
  }

  public String findNotReadyReason(
      String tenantId,
      String jobCode,
      LocalDate bizDate,
      List<JobDependencyRule> dependencies) {
    return evaluateDependencies(
        tenantId, jobCode, bizDate, snapshotDependencies(tenantId, jobCode, bizDate, dependencies));
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
    if (EmptyChecks.isNull(dependencies) || EmptyChecks.isEmpty(dependencies)) {
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
    if (EmptyChecks.isNull(rules) || EmptyChecks.isEmpty(rules)) {
      return List.of();
    }
    boolean needsPreviousBusinessDay = rules.stream()
        .anyMatch(rule -> JobDependencyRule.PREVIOUS_BIZ_DAY.equals(rule.bizDateAlignment()));
    String calendarCode = needsPreviousBusinessDay
        ? dependencyMapper.selectCalendarCodeByTenantAndJob(tenantId, downstreamJobCode)
        : null;
    BusinessCalendarEntity calendar = EmptyChecks.isNull(calendarCode)
            || EmptyChecks.isBlank(calendarCode)
        ? null
        : businessCalendarMapper.selectFirstByTenantAndCodeAndEnabled(tenantId, calendarCode, true);
    boolean missingCalendar = EmptyChecks.isNotNull(calendarCode)
        && EmptyChecks.isNotBlank(calendarCode)
        && EmptyChecks.isNull(calendar);
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
          Map<String, Object> value = new java.util.LinkedHashMap<>();
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
    if (EmptyChecks.isNull(json)
        || EmptyChecks.isBlank(json)
        || EmptyChecks.isNull(downstreamBizDate)) {
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
    if (EmptyChecks.isNotNull(rawError)
        && (EmptyChecks.isNull(resolutionError) || EmptyChecks.isBlank(resolutionError))) {
      throw new IllegalArgumentException(INVALID_DEPENDENCY_SNAPSHOT);
    }
    Object rawDate = values.get("upstreamBizDate");
    if (JobDependencyRule.SAME_DAY.equals(alignment)) {
      if (EmptyChecks.isNotNull(resolutionError)
          || (EmptyChecks.isNotNull(rawDate)
              && !downstreamBizDate.equals(decodeUpstreamDate(rawDate)))) {
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
