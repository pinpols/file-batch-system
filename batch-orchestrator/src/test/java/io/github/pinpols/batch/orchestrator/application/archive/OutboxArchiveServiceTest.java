package io.github.pinpols.batch.orchestrator.application.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.orchestrator.application.archive.OutboxArchiveService.ArchiveBatchResult;
import io.github.pinpols.batch.orchestrator.config.OutboxArchiveProperties;
import io.github.pinpols.batch.orchestrator.mapper.OutboxEventMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

@DisplayName("出箱归档服务: 开关短路, 冷热表搬运顺序与保留期口径")
class OutboxArchiveServiceTest {

  private OutboxEventMapper mapper;
  private OutboxArchiveProperties props;
  private OutboxArchiveService service;

  @BeforeEach
  void setUp() {
    mapper = mock(OutboxEventMapper.class);
    props = new OutboxArchiveProperties();
    service = new OutboxArchiveService(mapper, props);
  }

  @Test
  @DisplayName("归档开关关闭时直接返回未执行, 且不查询候选")
  void shouldShortCircuit_whenArchiveDisabled() {
    props.setEnabled(false);

    ArchiveBatchResult result = service.archivePublished();

    assertThat(result.executed()).isFalse();
    verify(mapper, never()).selectArchivableIds(anyString(), any(Instant.class), anyInt());
  }

  @Test
  @DisplayName("没有候选事件时返回已执行但计数为零, 不产生删除")
  void shouldReturnEmptyResult_whenNoCandidates() {
    props.setEnabled(true);
    when(mapper.selectArchivableIds(anyString(), any(Instant.class), anyInt()))
        .thenReturn(List.of());

    ArchiveBatchResult result = service.archivePublished();

    assertThat(result.executed()).isTrue();
    assertThat(result.candidates()).isZero();
    verify(mapper, never()).deleteByIds(any());
    verify(mapper, never()).deleteEventDeliveryLogsByOutboxIds(any());
  }

  @Test
  @DisplayName("有候选事件时先归档事件与投递日志, 再删除对应热表行")
  void shouldArchiveColdTablesBeforeDeleting_whenCandidatesExist() {
    props.setEnabled(true);
    props.setBatchSize(100);
    List<Long> ids = List.of(1L, 2L, 3L);
    when(mapper.selectArchivableIds(eq("PUBLISHED"), any(Instant.class), anyInt()))
        .thenReturn(ids);
    when(mapper.archiveEventDeliveryLogsByOutboxIds(ids)).thenReturn(5);
    when(mapper.archiveOutboxEventsByIds(ids)).thenReturn(3);
    when(mapper.deleteEventDeliveryLogsByOutboxIds(ids)).thenReturn(5);
    when(mapper.deleteByIds(ids)).thenReturn(3);

    ArchiveBatchResult result = service.archivePublished();

    assertThat(result.outboxDeleted()).isEqualTo(3);
    assertThat(result.deliveryLogsDeleted()).isEqualTo(5);
    InOrder inOrder = Mockito.inOrder(mapper);
    inOrder.verify(mapper).archiveEventDeliveryLogsByOutboxIds(ids);
    inOrder.verify(mapper).archiveEventOutboxRetriesByOutboxIds(ids);
    inOrder.verify(mapper).archiveOutboxEventsByIds(ids);
    inOrder.verify(mapper).deleteEventDeliveryLogsByOutboxIds(ids);
    inOrder.verify(mapper).deleteEventOutboxRetriesByOutboxIds(ids);
    inOrder.verify(mapper).deleteByIds(ids);
  }

  @Test
  @DisplayName("已发布与已放弃状态使用不同保留时长, 已放弃的截止时间更早")
  void shouldUseDifferentRetention_whenSelectingCandidates() {
    props.setEnabled(true);
    props.setPublishedRetentionDays(7);
    props.setGiveUpRetentionDays(30);
    when(mapper.selectArchivableIds(anyString(), any(Instant.class), anyInt()))
        .thenReturn(List.of());

    service.archivePublished();
    service.archiveGiveUp();

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(mapper).selectArchivableIds(eq("PUBLISHED"), cutoffCaptor.capture(), anyInt());
    Instant publishedCutoff = cutoffCaptor.getValue();
    verify(mapper).selectArchivableIds(eq("GIVE_UP"), cutoffCaptor.capture(), anyInt());
    Instant giveUpCutoff = cutoffCaptor.getValue();

    // GIVE_UP cutoff 应早于 PUBLISHED cutoff（30d 比 7d 远）
    assertThat(giveUpCutoff).isBefore(publishedCutoff);
  }

  @Test
  @DisplayName("候选数量等于批量大小时判定还有更多待归档")
  void shouldReportMore_whenCandidatesEqualBatchSize() {
    props.setEnabled(true);
    props.setBatchSize(3);
    when(mapper.selectArchivableIds(anyString(), any(Instant.class), anyInt()))
        .thenReturn(List.of(1L, 2L, 3L));
    when(mapper.deleteEventDeliveryLogsByOutboxIds(any())).thenReturn(0);
    when(mapper.deleteByIds(any())).thenReturn(3);

    ArchiveBatchResult result = service.archivePublished();
    assertThat(result.hasMore(props.getBatchSize())).isTrue();
  }
}
