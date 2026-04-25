package com.example.batch.orchestrator.application.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.orchestrator.application.archive.SuccessInstanceArchiveService.ArchiveBatchResult;
import com.example.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import com.example.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class SuccessInstanceArchiveServiceTest {

  private SuccessInstanceArchiveMapper mapper;
  private SuccessInstanceArchiveProperties props;
  private SuccessInstanceArchiveService service;

  @BeforeEach
  void setUp() {
    mapper = mock(SuccessInstanceArchiveMapper.class);
    props = new SuccessInstanceArchiveProperties();
    service = new SuccessInstanceArchiveService(mapper, props);
  }

  @Test
  void disabledShouldShortCircuit() {
    props.setEnabled(false);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isFalse();
    verify(mapper, never()).selectArchivableInstanceIds(any(Instant.class), anyInt());
  }

  @Test
  void noCandidatesShouldReturnEmpty() {
    props.setEnabled(true);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(List.of());

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isTrue();
    assertThat(result.candidates()).isZero();
    verify(mapper, never()).deleteJobInstancesByIds(any());
  }

  @Test
  void cascadeDeleteFollowsCorrectOrder() {
    props.setEnabled(true);
    props.setBatchSize(100);
    List<Long> ids = List.of(1L, 2L, 3L);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(ids);
    when(mapper.deleteJobInstancesByIds(ids)).thenReturn(3);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isTrue();
    assertThat(result.instancesDeleted()).isEqualTo(3);

    InOrder order = Mockito.inOrder(mapper);
    // FK 依赖顺序：先孙节点（job_step_instance / pipeline_step_run），中间表，最后根（job_instance）
    order.verify(mapper).deleteJobStepInstancesByInstanceIds(ids);
    order.verify(mapper).deleteJobTasksByInstanceIds(ids);
    order.verify(mapper).deletePipelineStepRunsByInstanceIds(ids);
    order.verify(mapper).nullifyPipelineInstanceFileIdByInstanceIds(ids);
    order.verify(mapper).deleteFileDispatchRecordsByInstanceIds(ids);
    order.verify(mapper).deletePipelineInstancesByInstanceIds(ids);
    order.verify(mapper).deleteJobPartitionsByInstanceIds(ids);
    order.verify(mapper).deleteWorkflowNodeRunsByInstanceIds(ids);
    order.verify(mapper).deleteWorkflowRunsByInstanceIds(ids);
    order.verify(mapper).deleteJobExecutionLogsByInstanceIds(ids);
    order.verify(mapper).deleteCompensationCommandsByInstanceIds(ids);
    order.verify(mapper).nullifyParentInstanceIdByParentIds(ids);
    order.verify(mapper).deleteJobInstancesByIds(ids);
  }

  @Test
  void hasMoreReturnsTrueAtBatchSize() {
    props.setEnabled(true);
    props.setBatchSize(3);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt()))
        .thenReturn(List.of(1L, 2L, 3L));
    when(mapper.deleteJobInstancesByIds(any())).thenReturn(3);

    ArchiveBatchResult result = service.archiveOnce();
    assertThat(result.hasMore(3)).isTrue();
  }

  @Test
  void retentionDaysClampedToOne() {
    props.setEnabled(true);
    props.setRetentionDays(-5);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(List.of());

    service.archiveOnce();

    org.mockito.ArgumentCaptor<Instant> cutoffCaptor =
        org.mockito.ArgumentCaptor.forClass(Instant.class);
    verify(mapper).selectArchivableInstanceIds(cutoffCaptor.capture(), anyInt());
    Instant cutoff = cutoffCaptor.getValue();
    Instant now = Instant.now();
    assertThat(cutoff).isBefore(now.minusSeconds(86_400 - 5));
    assertThat(cutoff).isAfter(now.minusSeconds(86_400 + 5));
  }
}
