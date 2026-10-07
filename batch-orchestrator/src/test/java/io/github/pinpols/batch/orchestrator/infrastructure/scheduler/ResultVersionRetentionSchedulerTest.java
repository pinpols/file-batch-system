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
import io.github.pinpols.batch.orchestrator.config.BatchDayDryRunProperties;
import io.github.pinpols.batch.orchestrator.config.ResultVersionRetentionProperties;
import io.github.pinpols.batch.orchestrator.domain.entity.ResultVersionEntity;
import io.github.pinpols.batch.orchestrator.infrastructure.OrchestratorGracefulShutdown;
import io.github.pinpols.batch.orchestrator.mapper.ResultVersionMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("结果版本保留调度器: 过期版本降级归档, 归档清理与试运行版本归档的批次处理")
class ResultVersionRetentionSchedulerTest {

  private ResultVersionMapper mapper;
  private ResultVersionRetentionProperties properties;
  private BatchDayDryRunProperties dryRunProperties;
  private OrchestratorGracefulShutdown gracefulShutdown;
  private ResultVersionRetentionScheduler scheduler;

  @BeforeEach
  void setUp() {
    mapper = mock(ResultVersionMapper.class);
    properties = new ResultVersionRetentionProperties();
    properties.setEnabled(true);
    properties.setBatchSize(500);
    properties.setSupersededDays(90);
    properties.setArchivedDays(365);
    dryRunProperties = new BatchDayDryRunProperties();
    dryRunProperties.setRetentionDays(7);
    gracefulShutdown = mock(OrchestratorGracefulShutdown.class);
    when(gracefulShutdown.isDraining()).thenReturn(false);
    BatchDateTimeSupport dateTimeSupport = new BatchDateTimeSupport(
        Clock.systemUTC(), new BatchTimezoneProvider(new BatchTimezoneProperties()));
    scheduler = new ResultVersionRetentionScheduler(
        mapper, properties, dryRunProperties, gracefulShutdown, dateTimeSupport);
  }

  @Test
  @DisplayName("扫描出过期的被取代版本时逐条降级归档, 返回值等于成功归档条数")
  void shouldDemoteEachStaleRow_whenBatchScanned() {
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
  @DisplayName("查询无命中时不执行任何归档, 返回零")
  void shouldArchiveNothing_whenQueryReturnsEmpty() {
    when(mapper.selectSupersededOlderThan(any(), anyInt())).thenReturn(List.of());

    int archived = scheduler.demoteSupersededBatch(Instant.now());

    assertThat(archived).isZero();
    verify(mapper, never()).archiveSuperseded(anyString(), anyLong(), any(), anyBoolean());
  }

  @Test
  @DisplayName("清理阶段只删除保留期查询返回的归档行, 返回实际删除条数")
  void shouldPurgeOnlyRowsFromRetentionQuery() {
    ResultVersionEntity row =
        ResultVersionEntity.builder().id(7L).tenantId("t1").status("ARCHIVED").build();
    when(mapper.selectArchivedOlderThan(any(), eq(500))).thenReturn(List.of(row));
    when(mapper.deleteArchived("t1", 7L)).thenReturn(1);

    int deleted = scheduler.purgeArchivedBatch(Instant.parse("2026-08-15T00:00:00Z"));

    assertThat(deleted).isEqualTo(1);
    verify(mapper).deleteArchived("t1", 7L);
  }

  @Test
  @DisplayName("没有达到清理条件的归档行时不执行删除, 返回零")
  void shouldSkipPurge_whenNoRowEligible() {
    when(mapper.selectArchivedOlderThan(any(), eq(500))).thenReturn(List.of());

    int deleted = scheduler.purgeArchivedBatch(Instant.now());

    assertThat(deleted).isZero();
    verify(mapper, never()).deleteArchived(anyString(), anyLong());
  }

  @Test
  @DisplayName("试运行版本按独立保留天数计算窗口并归档, 查询下界随该天数前移")
  void shouldArchiveDryRunRows_whenIndependentWindowElapsed() {
    ResultVersionEntity row =
        ResultVersionEntity.builder().id(8L).tenantId("t1").status("DRY_RUN").build();
    when(mapper.selectDryRunOlderThan(any(), eq(500))).thenReturn(List.of(row));
    when(mapper.archiveAndDeleteDryRun(eq("t1"), eq(8L), any())).thenReturn(1);

    int archived = scheduler.archiveDryRunBatch(Instant.parse("2026-08-15T00:00:00Z"));

    assertThat(archived).isEqualTo(1);
    verify(mapper).selectDryRunOlderThan(eq(Instant.parse("2026-08-08T00:00:00Z")), eq(500));
    verify(mapper).archiveAndDeleteDryRun(eq("t1"), eq(8L), any());
  }

  @Test
  @DisplayName("调度开关关闭时跳过整批扫描, 不查询过期版本")
  void shouldSkipScan_whenSchedulerDisabled() {
    properties.setEnabled(false);

    scheduler.scheduledScan();

    verify(mapper, never()).selectSupersededOlderThan(any(), anyInt());
  }

  @Test
  @DisplayName("应用处于停机排空阶段时跳过整批扫描, 不查询过期版本")
  void shouldSkipScan_whenShutdownDraining() {
    when(gracefulShutdown.isDraining()).thenReturn(true);

    scheduler.scheduledScan();

    verify(mapper, never()).selectSupersededOlderThan(any(), anyInt());
  }
}
