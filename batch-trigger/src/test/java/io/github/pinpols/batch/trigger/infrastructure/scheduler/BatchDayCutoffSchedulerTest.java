package io.github.pinpols.batch.trigger.infrastructure.scheduler;

import io.github.pinpols.batch.trigger.domain.param.BatchDayInstanceMarkCutoffParam;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.trigger.infrastructure.TriggerGracefulShutdown;
import io.github.pinpols.batch.trigger.mapper.BatchDayInstanceMapper;
import io.github.pinpols.batch.trigger.support.BatchDayCutoffCandidate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("批处理日切扫描器:按租户时区与 cutoffTime 判定到点候选,再做 CAS 切日")
class BatchDayCutoffSchedulerTest {

  private BatchDayInstanceMapper batchDayInstanceMapper;
  private TriggerGracefulShutdown triggerGracefulShutdown;
  private BatchDayCutoffScheduler scheduler;

  @BeforeEach
  void setUp() {
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    triggerGracefulShutdown = mock(TriggerGracefulShutdown.class);
    scheduler = new BatchDayCutoffScheduler(
        batchDayInstanceMapper,
        triggerGracefulShutdown,
        new BatchTimezoneProvider(new BatchTimezoneProperties()),
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties())));
  }

  @Test
  @DisplayName("已到 cutoffTime 的候选按 CAS 标记切日并带上当前时间,未到点的候选不得被切日")
  void shouldCutoffDueCandidatesAndSkipFutureCandidates() {
    BatchDayCutoffCandidate due = candidate(1L, LocalTime.MIN);
    BatchDayCutoffCandidate future = candidate(2L, LocalTime.MAX);
    when(batchDayInstanceMapper.selectOpenCutoffCandidates()).thenReturn(List.of(due, future));
    when(batchDayInstanceMapper.markCutoff(
            org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.id(), 1L) && java.util.Objects.equals(parameters.tenantId(), "t1") && java.util.Objects.equals(parameters.calendarCode(), "CAL") && java.util.Objects.equals(parameters.bizDate(), due.getBizDate()))))
        .thenReturn(1);

    scheduler.cutoff();

    ArgumentCaptor<BatchDayInstanceMarkCutoffParam> cutoffCaptor = ArgumentCaptor.forClass(BatchDayInstanceMarkCutoffParam.class);
    verify(batchDayInstanceMapper)
        .markCutoff(cutoffCaptor.capture());
    assertThat(cutoffCaptor.getValue().id()).isEqualTo(1L);
    assertThat(cutoffCaptor.getValue().tenantId()).isEqualTo("t1");
    assertThat(cutoffCaptor.getValue().calendarCode()).isEqualTo("CAL");
    assertThat(cutoffCaptor.getValue().bizDate()).isEqualTo(due.getBizDate());
    assertThat(cutoffCaptor.getValue().cutoffAt()).isNotNull();
    verify(batchDayInstanceMapper, never())
        .markCutoff(org.mockito.ArgumentMatchers.argThat(parameters -> parameters != null && java.util.Objects.equals(parameters.id(), 2L) && java.util.Objects.equals(parameters.tenantId(), "t1") && java.util.Objects.equals(parameters.calendarCode(), "CAL") && java.util.Objects.equals(parameters.bizDate(), future.getBizDate())));
  }

  @Test
  @DisplayName("没有 OPEN 候选时扫描直接返回,不产生任何切日更新,避免空转打库")
  void shouldDoNothingWhenNoCandidates() {
    when(batchDayInstanceMapper.selectOpenCutoffCandidates()).thenReturn(List.of());

    scheduler.cutoff();

    verify(batchDayInstanceMapper, never()).markCutoff(org.mockito.ArgumentMatchers.any(BatchDayInstanceMarkCutoffParam.class));
  }

  private BatchDayCutoffCandidate candidate(Long id, LocalTime cutoffTime) {
    BatchDayCutoffCandidate candidate = new BatchDayCutoffCandidate();
    candidate.setId(id);
    candidate.setTenantId("t1");
    candidate.setCalendarCode("CAL");
    candidate.setBizDate(LocalDate.of(2026, Month.MARCH, 27));
    candidate.setDayStatus("OPEN");
    candidate.setTimezone("UTC");
    candidate.setCutoffTime(cutoffTime);
    return candidate;
  }
}
