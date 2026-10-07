package io.github.pinpols.batch.trigger.infrastructure.scheduler;

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
            eq(1L), eq("t1"), eq("CAL"), eq(due.getBizDate()), any()))
        .thenReturn(1);

    scheduler.cutoff();

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(batchDayInstanceMapper)
        .markCutoff(eq(1L), eq("t1"), eq("CAL"), eq(due.getBizDate()), cutoffCaptor.capture());
    assertThat(cutoffCaptor.getValue()).isNotNull();
    verify(batchDayInstanceMapper, never())
        .markCutoff(eq(2L), eq("t1"), eq("CAL"), eq(future.getBizDate()), any());
  }

  @Test
  @DisplayName("没有 OPEN 候选时扫描直接返回,不产生任何切日更新,避免空转打库")
  void shouldDoNothingWhenNoCandidates() {
    when(batchDayInstanceMapper.selectOpenCutoffCandidates()).thenReturn(List.of());

    scheduler.cutoff();

    verify(batchDayInstanceMapper, never()).markCutoff(any(), any(), any(), any(), any());
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
