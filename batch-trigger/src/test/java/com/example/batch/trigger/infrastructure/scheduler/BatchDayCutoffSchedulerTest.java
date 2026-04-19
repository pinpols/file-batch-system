package com.example.batch.trigger.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.config.BatchTimezoneProperties;
import com.example.batch.common.config.BatchTimezoneProvider;
import com.example.batch.trigger.infrastructure.TriggerGracefulShutdown;
import com.example.batch.trigger.mapper.BatchDayInstanceMapper;
import com.example.batch.trigger.support.BatchDayCutoffCandidate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BatchDayCutoffSchedulerTest {

  private BatchDayInstanceMapper batchDayInstanceMapper;
  private TriggerGracefulShutdown triggerGracefulShutdown;
  private BatchDayCutoffScheduler scheduler;

  @BeforeEach
  void setUp() {
    batchDayInstanceMapper = mock(BatchDayInstanceMapper.class);
    triggerGracefulShutdown = mock(TriggerGracefulShutdown.class);
    scheduler =
        new BatchDayCutoffScheduler(
            batchDayInstanceMapper,
            triggerGracefulShutdown,
            new BatchTimezoneProvider(new BatchTimezoneProperties()));
  }

  @Test
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
    candidate.setBizDate(LocalDate.of(2026, 3, 27));
    candidate.setDayStatus("OPEN");
    candidate.setTimezone("UTC");
    candidate.setCutoffTime(cutoffTime);
    return candidate;
  }
}
