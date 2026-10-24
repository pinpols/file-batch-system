package io.github.pinpols.batch.trigger.service;

import io.github.pinpols.batch.trigger.domain.param.ScheduledOccurrenceRecordCoverageParam;


import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.utils.CodeNormalizer;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.trigger.infrastructure.QuartzScheduledFireEnumerator;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrencePlanBatchParam;
import io.github.pinpols.batch.trigger.support.CalendarBizDateDefinition;
import io.github.pinpols.batch.trigger.support.ScheduledOccurrenceCoverage;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.quartz.Trigger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 尚未创建 Job Instance 的延迟子作业应保持节点 READY，等待释放调度器处理。 */
@Service
@RequiredArgsConstructor
public class TriggerScheduledOccurrencePlanner {

  private static final Duration LOOKAHEAD = Duration.ofDays(2);
  private static final int MAX_OCCURRENCES_PER_TRIGGER = 100_000;
  private static final int MAX_FIRES_PER_INSERT = 1_000;

  private final QuartzScheduledFireEnumerator fireEnumerator;
  private final TriggerCalendarDefinitionProvider calendarDefinitionProvider;
  private final CalendarBizDateResolver bizDateResolver;
  private final BatchTimezoneProvider timezoneProvider;
  private final ScheduledOccurrenceMapper occurrenceMapper;

  @Transactional
  public int materialize(TriggerDescriptor descriptor, Trigger trigger, Instant now) {
    boolean occurrenceInputIsIncomplete =
        EmptyChecks.isNull(descriptor)
        || EmptyChecks.isNull(trigger)
        || EmptyChecks.isBlank(descriptor.getTenantId())
        || EmptyChecks.isNull(descriptor.getWorkflowDefinitionId())
        || EmptyChecks.isBlank(descriptor.getWorkflowCode());
    if (occurrenceInputIsIncomplete) {
      return 0;
    }
    int scheduleVersion =
        EmptyChecks.isNull(descriptor.getWorkflowVersion()) ? 0 : descriptor.getWorkflowVersion();
    long configurationRevision =
        occurrenceMapper.selectTenantConfigurationRevision(descriptor.getTenantId());
    Instant configurationUpdatedAt =
        occurrenceMapper.selectTenantConfigurationUpdatedAt(descriptor.getTenantId());
    String calendarCode = CodeNormalizer.toConfigFormOrNull(descriptor.getCalendarCode());
    CalendarBizDateDefinition calendar =
        calendarDefinitionProvider.load(descriptor.getTenantId(), calendarCode);
    ZoneId fallbackZone = timezoneProvider.resolveOrDefault(descriptor.getTimezone());
    ScheduledOccurrenceCoverage coverage = occurrenceMapper.selectCoverage(
        descriptor.getTenantId(),
        descriptor.getWorkflowDefinitionId(),
        scheduleVersion,
        calendarCode);
    Instant coverageFrom =
        EmptyChecks.isNull(coverage) ? now.minusMillis(1) : coverage.coverageFrom();
    boolean coverageCurrent = EmptyChecks.isNotNull(coverage)
        && occurrenceMapper.isCoverageCurrentForTenantConfiguration(
            descriptor.getTenantId(), descriptor.getWorkflowDefinitionId());
    Instant scanAfter;
    if (coverageCurrent) {
      scanAfter = coverage.coverageThrough().minusMillis(1);
    } else {
      Instant replanFrom = configurationUpdatedAt;
      if (EmptyChecks.isNull(replanFrom)) {
        replanFrom = now;
      }
      if (replanFrom.isBefore(coverageFrom)) {
        replanFrom = coverageFrom;
      }
      scanAfter = replanFrom.minusMillis(1);
    }
    Instant requestedThrough = now.plus(LOOKAHEAD);
    Instant requestedCoverageThrough =
        EmptyChecks.isNull(coverage) || coverage.coverageThrough().isBefore(requestedThrough)
            ? requestedThrough
            : coverage.coverageThrough();
    ScanWindow scanWindow = enumerateBoundedWindow(trigger, scanAfter, requestedCoverageThrough);
    List<Instant> fireTimes = scanWindow.fireTimes();
    List<ScheduledOccurrencePlanBatchParam.Fire> batch = new ArrayList<>(MAX_FIRES_PER_INSERT);
    for (Instant fireTime : fireTimes) {
      LocalDate bizDate = bizDateResolver.resolve(fireTime, fallbackZone, calendar);
      batch.add(new ScheduledOccurrencePlanBatchParam.Fire(fireTime, bizDate));
      if (batch.size() == MAX_FIRES_PER_INSERT) {
        insertBatch(descriptor, scheduleVersion, calendarCode, batch);
        batch.clear();
      }
    }
    if (EmptyChecks.isNotEmpty(batch)) {
      insertBatch(descriptor, scheduleVersion, calendarCode, batch);
    }
    occurrenceMapper.supersedeFuturePlanned(
        descriptor.getTenantId(), descriptor.getWorkflowDefinitionId(), scheduleVersion, now);
    long currentConfigurationRevision =
        occurrenceMapper.lockTenantConfigurationRevision(descriptor.getTenantId());
    if (currentConfigurationRevision != configurationRevision) {
      throw new IllegalStateException(
          "Tenant configuration changed during scheduled occurrence materialization");
    }
    ScheduledOccurrenceRecordCoverageParam scheduledOccurrenceRecordCoverageParam = ScheduledOccurrenceRecordCoverageParam.builder()
        .tenantId(descriptor.getTenantId())
        .workflowDefinitionId(descriptor.getWorkflowDefinitionId())
        .configurationRevision(configurationRevision)
        .scheduleVersion(scheduleVersion)
        .calendarCode(calendarCode)
        .coverageFrom(coverageFrom)
        .coverageThrough(scanWindow.coverageThrough())
        .completedAt(now)
        .build();
    occurrenceMapper.recordCoverage(
        scheduledOccurrenceRecordCoverageParam);
    return fireTimes.size();
  }

  private ScanWindow enumerateBoundedWindow(
      Trigger trigger, Instant scanAfter, Instant requestedCoverageThrough) {
    Instant coverageThrough = requestedCoverageThrough;
    while (true) {
      try {
        List<Instant> fireTimes = fireEnumerator.enumerate(
            trigger, scanAfter, coverageThrough, MAX_OCCURRENCES_PER_TRIGGER);
        return new ScanWindow(fireTimes, coverageThrough);
      } catch (QuartzScheduledFireEnumerator.OccurrenceLimitExceededException exception) {
        long windowMillis = Duration.between(scanAfter, coverageThrough).toMillis();
        if (windowMillis <= 1) {
          throw exception;
        }
        long halfWindowMillis = windowMillis / 2;
        long nextWindowMillis = Math.max(1, halfWindowMillis);
        coverageThrough = scanAfter.plusMillis(nextWindowMillis);
      }
    }
  }

  private record ScanWindow(List<Instant> fireTimes, Instant coverageThrough) {}

  private void insertBatch(
      TriggerDescriptor descriptor,
      int scheduleVersion,
      String calendarCode,
      List<ScheduledOccurrencePlanBatchParam.Fire> fires) {
    String tenantId = descriptor.getTenantId();
    Long workflowDefinitionId = descriptor.getWorkflowDefinitionId();
    String workflowCode = descriptor.getWorkflowCode();
    List<ScheduledOccurrencePlanBatchParam.Fire> fireSnapshot = List.copyOf(fires);
    ScheduledOccurrencePlanBatchParam batchParam = new ScheduledOccurrencePlanBatchParam(
        tenantId, workflowDefinitionId, workflowCode, scheduleVersion, calendarCode, fireSnapshot);
    occurrenceMapper.insertPlannedBatch(batchParam);
  }

  /** 实例创建前被明确拒绝的子请求可记录为节点失败。 */
  public int supersedeFuturePlans(String tenantId, String workflowCode, Instant now) {
    return occurrenceMapper.supersedeAllFuturePlanned(tenantId, workflowCode, now);
  }
}
