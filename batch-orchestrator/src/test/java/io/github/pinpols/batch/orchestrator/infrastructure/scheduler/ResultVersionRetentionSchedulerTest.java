package io.github.pinpols.batch.orchestrator.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.BatchTimezoneProperties;
import io.github.pinpols.batch.common.config.BatchTimezoneProvider;
import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.config.ResultVersionRetentionProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResultVersionRetentionSchedulerTest {

  private ResultVersionMapper mapper;
  private ResultVersionRetentionProperties properties;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private ResultVersionRetentionScheduler scheduler;

  @BeforeEach
  void setUp() {
    mapper = mock(ResultVersionMapper.class);
    properties = new ResultVersionRetentionProperties();
    properties.setEnabled(true);
    properties.setBatchSize(500);
    properties.setSupersededDays(90);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    BatchDateTimeSupport dateTimeSupport =
        new BatchDateTimeSupport(
            Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    scheduler =
        new ResultVersionRetentionScheduler(mapper, properties, gracefulShutdown, dateTimeSupport);
  }

  @Test
  void demotesEachStaleSupersededRow() {
    ResultVersionEntity r1 =
        ResultVersionEntity.builder().id(1L).tenantId("t1").status("SUPERSEDED").build();
    ResultVersionEntity r2 =
        ResultVersionEntity.builder().id(2L).tenantId("t1").status("SUPERSEDED").build();
    when(mapper.selectSupersededOlderThan(any(), eq(500))).thenReturn(List.of(r1, r2));
    when(mapper.archiveSuperseded(eq("t1"), anyLong(), any(), eq(true))).thenReturn(1);

    int archived = scheduler.demoteSupersededBatch(Instant.parse("2026-08-15T00:00:00Z"));

    assertThat(archived).isEqualTo(2);
    verify(mapper, times(2)).archiveSuperseded(eq("t1"), anyLong(), any(), eq(true));
  }

  @Test
  void emptyResultIsNoop() {
    when(mapper.selectSupersededOlderThan(any(), anyInt())).thenReturn(List.of());

    int archived = scheduler.demoteSupersededBatch(Instant.now());

    assertThat(archived).isZero();
    verify(mapper, never()).archiveSuperseded(anyString(), anyLong(), any(), anyBoolean());
  }

  @Test
  void disabledSchedulerSkipsScan() {
    properties.setEnabled(false);

    scheduler.scheduledScan();

    verify(mapper, never()).selectSupersededOlderThan(any(), anyInt());
  }

  @Test
  void drainingShutdownSkipsScan() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler.scheduledScan();

    verify(mapper, never()).selectSupersededOlderThan(any(), anyInt());
  }
}
