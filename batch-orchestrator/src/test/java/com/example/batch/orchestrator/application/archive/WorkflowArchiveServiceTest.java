package com.example.batch.orchestrator.application.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.application.archive.WorkflowArchiveService.ArchiveBatchResult;
import com.example.batch.orchestrator.config.WorkflowArchiveProperties;
import com.example.batch.orchestrator.mapper.WorkflowRunMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WorkflowArchiveServiceTest {

  private WorkflowRunMapper mapper;
  private WorkflowArchiveProperties props;
  private WorkflowArchiveService service;

  @BeforeEach
  void setUp() {
    mapper = mock(WorkflowRunMapper.class);
    props = new WorkflowArchiveProperties();
    service = new WorkflowArchiveService(mapper, props);
  }

  @Test
  void disabledShouldShortCircuit() {
    props.setEnabled(false);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isFalse();
    assertThat(result.candidates()).isZero();
    verify(mapper, never()).selectArchivableIds(any(Instant.class), anyInt());
  }

  @Test
  void noCandidatesShouldReturnEmptyResult() {
    props.setEnabled(true);
    when(mapper.selectArchivableIds(any(Instant.class), anyInt())).thenReturn(List.of());

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isTrue();
    assertThat(result.candidates()).isZero();
    verify(mapper, never()).deleteByIds(any());
    verify(mapper, never()).deleteNodeRunsByWorkflowRunIds(any());
  }

  @Test
  void candidatesShouldDeleteNodeRunsBeforeRuns() {
    props.setEnabled(true);
    props.setBatchSize(100);
    List<Long> ids = List.of(1L, 2L, 3L);
    when(mapper.selectArchivableIds(any(Instant.class), anyInt())).thenReturn(ids);
    when(mapper.deleteNodeRunsByWorkflowRunIds(ids)).thenReturn(7);
    when(mapper.deleteByIds(ids)).thenReturn(3);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isTrue();
    assertThat(result.candidates()).isEqualTo(3);
    assertThat(result.workflowRunsDeleted()).isEqualTo(3);
    assertThat(result.workflowNodeRunsDeleted()).isEqualTo(7);
    // 顺序断言：node_runs 先于 runs
    var inOrder = inOrder(mapper);
    inOrder.verify(mapper).deleteNodeRunsByWorkflowRunIds(ids);
    inOrder.verify(mapper).deleteByIds(ids);
  }

  @Test
  void hasMoreReturnsTrueWhenCandidatesEqualBatchSize() {
    props.setEnabled(true);
    props.setBatchSize(3);
    when(mapper.selectArchivableIds(any(Instant.class), anyInt())).thenReturn(List.of(1L, 2L, 3L));
    when(mapper.deleteNodeRunsByWorkflowRunIds(any())).thenReturn(0);
    when(mapper.deleteByIds(any())).thenReturn(3);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.hasMore(props.getBatchSize())).isTrue();
  }

  @Test
  void hasMoreReturnsFalseWhenCandidatesUnderBatchSize() {
    props.setEnabled(true);
    props.setBatchSize(100);
    when(mapper.selectArchivableIds(any(Instant.class), anyInt())).thenReturn(List.of(1L, 2L));
    when(mapper.deleteNodeRunsByWorkflowRunIds(any())).thenReturn(0);
    when(mapper.deleteByIds(any())).thenReturn(2);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.hasMore(props.getBatchSize())).isFalse();
  }

  @Test
  void retentionDaysClampedToMinimumOne() {
    props.setEnabled(true);
    props.setRetentionDays(0); // 0 / 负值 → clamp 到 1，避免删 RUNNING 兄弟事务
    when(mapper.selectArchivableIds(any(Instant.class), anyInt())).thenReturn(List.of());

    service.archiveOnce();

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(mapper, times(1)).selectArchivableIds(cutoffCaptor.capture(), anyInt());
    Instant cutoff = cutoffCaptor.getValue();
    Instant now = Instant.now();
    // cutoff 应大约在 1 天前，允许 5s 误差
    assertThat(cutoff).isBefore(now.minusSeconds(86_400 - 5));
    assertThat(cutoff).isAfter(now.minusSeconds(86_400 + 5));
  }
}
