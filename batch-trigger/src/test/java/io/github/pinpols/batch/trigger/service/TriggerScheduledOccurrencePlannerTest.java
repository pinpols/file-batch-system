package io.github.pinpols.batch.trigger.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.trigger.infrastructure.QuartzScheduledFireEnumerator;
import io.github.pinpols.batch.trigger.mapper.ScheduledOccurrenceMapper;
import io.github.pinpols.batch.trigger.support.ScheduledOccurrenceCoverage;
import io.github.pinpols.batch.trigger.support.TriggerDescriptor;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.Trigger;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quartz 预期 occurrence 物化：复用共享业务日解析并按计划版本幂等写入")
class TriggerScheduledOccurrencePlannerTest {

  @Mock
  private QuartzScheduledFireEnumerator fireEnumerator;

  @Mock
  private TriggerCalendarDefinitionProvider calendarDefinitionProvider;

  @Mock
  private BatchTimezoneProvider timezoneProvider;

  @Mock
  private ScheduledOccurrenceMapper occurrenceMapper;

  @Mock
  private Trigger quartzTrigger;

  @Test
  @DisplayName("Quartz 窗口中的每个 fire 使用同一业务日 resolver，并投影租户日历和定义版本")
  void shouldMaterializeQuartzFiresWithResolvedBizDateAndScheduleVersion() {
    TriggerScheduledOccurrencePlanner planner = new TriggerScheduledOccurrencePlanner(
        fireEnumerator,
        calendarDefinitionProvider,
        new CalendarBizDateResolver(timezoneProvider),
        timezoneProvider,
        occurrenceMapper);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId("t1");
    descriptor.setWorkflowDefinitionId(701L);
    descriptor.setWorkflowCode("WF_EXPORT");
    descriptor.setWorkflowVersion(7);
    descriptor.setJobDefinitionVersion(7);
    descriptor.setCalendarCode("biz-day");
    descriptor.setTimezone("Asia/Shanghai");
    Instant now = Instant.parse("2026-10-13T00:00:00Z");
    Instant firstFire = Instant.parse("2026-10-13T22:00:00Z");
    Instant secondFire = Instant.parse("2026-10-14T22:00:00Z");
    Instant previousCoverageFrom = now.minusSeconds(4 * 86400L);
    Instant previousCoverageThrough = now.minusSeconds(2 * 86400L);
    when(occurrenceMapper.selectCoverage("t1", 701L, 7, "biz_day"))
        .thenReturn(new ScheduledOccurrenceCoverage(previousCoverageFrom, previousCoverageThrough));
    when(occurrenceMapper.isCoverageCurrentForTenantConfiguration("t1", 701L)).thenReturn(true);
    when(fireEnumerator.enumerate(any(), any(), any(), anyInt()))
        .thenReturn(List.of(firstFire, secondFire));
    when(timezoneProvider.resolveOrDefault("Asia/Shanghai")).thenReturn(ZoneId.of("Asia/Shanghai"));

    int materialized = planner.materialize(descriptor, quartzTrigger, now);

    LocalDate expectedDate = LocalDate.of(2026, 10, 14);
    verify(calendarDefinitionProvider).load("t1", "biz_day");
    verify(fireEnumerator)
        .enumerate(
            quartzTrigger,
            previousCoverageThrough.minusMillis(1),
            now.plusSeconds(172800),
            100_000);
    var batch = org.mockito.ArgumentCaptor.forClass(
        io.github.pinpols.batch.trigger.mapper.ScheduledOccurrencePlanBatchParam.class);
    verify(occurrenceMapper).insertPlannedBatch(batch.capture());
    org.assertj.core.api.Assertions.assertThat(batch.getValue().tenantId()).isEqualTo("t1");
    org.assertj.core.api.Assertions.assertThat(batch.getValue().workflowDefinitionId())
        .isEqualTo(701L);
    org.assertj.core.api.Assertions.assertThat(batch.getValue().fires())
        .containsExactly(
            new io.github.pinpols.batch.trigger.mapper.ScheduledOccurrencePlanBatchParam.Fire(
                firstFire, expectedDate),
            new io.github.pinpols.batch.trigger.mapper.ScheduledOccurrencePlanBatchParam.Fire(
                secondFire, LocalDate.of(2026, 10, 15)));
    verify(occurrenceMapper).supersedeFuturePlanned("t1", 701L, 7, now);
    verify(occurrenceMapper)
        .recordCoverage(
            "t1", 701L, 7, "biz_day", previousCoverageFrom, now.plusSeconds(172800), now);
    org.assertj.core.api.Assertions.assertThat(materialized).isEqualTo(2);
  }

  @Test
  @DisplayName("配置更新使 coverage 过期后，从当前时刻重扫未来 occurrence 并保留原覆盖起点")
  void shouldRescanFutureOccurrencesWhenCalendarConfigurationChanged() {
    TriggerScheduledOccurrencePlanner planner = new TriggerScheduledOccurrencePlanner(
        fireEnumerator,
        calendarDefinitionProvider,
        new CalendarBizDateResolver(timezoneProvider),
        timezoneProvider,
        occurrenceMapper);
    TriggerDescriptor descriptor = new TriggerDescriptor();
    descriptor.setTenantId("t1");
    descriptor.setWorkflowDefinitionId(701L);
    descriptor.setWorkflowCode("WF_EXPORT");
    descriptor.setWorkflowVersion(7);
    descriptor.setCalendarCode("biz_day");
    Instant now = Instant.parse("2026-10-13T00:00:00Z");
    Instant configurationEffectiveAt = now.minusSeconds(60);
    Instant previousCoverageFrom = now.minusSeconds(86_400);
    Instant previousCoverageThrough = now.plusSeconds(86_400);
    when(occurrenceMapper.selectCoverage("t1", 701L, 7, "biz_day"))
        .thenReturn(new ScheduledOccurrenceCoverage(previousCoverageFrom, previousCoverageThrough));
    when(occurrenceMapper.isCoverageCurrentForTenantConfiguration("t1", 701L)).thenReturn(false);
    when(occurrenceMapper.selectTenantConfigurationUpdatedAt("t1"))
        .thenReturn(configurationEffectiveAt);
    when(fireEnumerator.enumerate(any(), any(), any(), anyInt())).thenReturn(List.of());

    planner.materialize(descriptor, quartzTrigger, now);

    verify(fireEnumerator)
        .enumerate(
            quartzTrigger,
            configurationEffectiveAt.minusMillis(1),
            now.plusSeconds(172800),
            100_000);
    verify(occurrenceMapper)
        .recordCoverage(
            "t1", 701L, 7, "biz_day", previousCoverageFrom, now.plusSeconds(172800), now);
  }
}
