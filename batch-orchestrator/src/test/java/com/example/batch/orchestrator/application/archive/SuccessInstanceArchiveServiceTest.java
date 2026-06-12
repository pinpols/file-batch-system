package com.example.batch.orchestrator.application.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.application.archive.SuccessInstanceArchiveService.ArchiveBatchResult;
import com.example.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import com.example.batch.orchestrator.domain.value.ArchivableInstanceRef;
import com.example.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

class SuccessInstanceArchiveServiceTest {

  private static final String TENANT = "t1";

  private SuccessInstanceArchiveMapper mapper;
  private SuccessInstanceArchiveProperties props;
  private SuccessInstanceArchiveService service;

  @BeforeEach
  void setUp() {
    mapper = mock(SuccessInstanceArchiveMapper.class);
    props = new SuccessInstanceArchiveProperties();
    service = new SuccessInstanceArchiveService(mapper, props);
  }

  private static List<ArchivableInstanceRef> refs(String tenantId, Long... ids) {
    return List.of(ids).stream().map(id -> new ArchivableInstanceRef(tenantId, id)).toList();
  }

  @Test
  void disabledShouldShortCircuit() {
    props.setEnabled(false);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isFalse();
    verify(mapper, never()).selectArchivableInstances(any(Instant.class), anyInt());
  }

  @Test
  void noCandidatesShouldReturnEmpty() {
    props.setEnabled(true);
    when(mapper.selectArchivableInstances(any(Instant.class), anyInt())).thenReturn(List.of());

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isTrue();
    assertThat(result.candidates()).isZero();
    verify(mapper, never()).deleteJobInstancesByIds(any(), any());
  }

  @Test
  void archiveColdTablesBeforeCascadeDelete() {
    props.setEnabled(true);
    props.setBatchSize(100);
    List<Long> ids = List.of(1L, 2L, 3L);
    when(mapper.selectArchivableInstances(any(Instant.class), anyInt()))
        .thenReturn(refs(TENANT, 1L, 2L, 3L));
    when(mapper.archiveJobInstancesByIds(eq(TENANT), eq(ids))).thenReturn(3);
    when(mapper.deleteJobInstancesByIds(eq(TENANT), eq(ids))).thenReturn(3);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isTrue();
    assertThat(result.instancesDeleted()).isEqualTo(3);

    InOrder order = Mockito.inOrder(mapper);
    order.verify(mapper).archiveJobInstancesByIds(TENANT, ids);
    order.verify(mapper).archiveJobPartitionsByInstanceIds(TENANT, ids);
    order.verify(mapper).archiveJobTasksByInstanceIds(TENANT, ids);
    order.verify(mapper).archiveJobStepInstancesByInstanceIds(TENANT, ids);
    order.verify(mapper).archivePipelineInstancesByInstanceIds(TENANT, ids);
    order.verify(mapper).archivePipelineStepRunsByInstanceIds(TENANT, ids);
    order.verify(mapper).archiveFileDispatchRecordsByInstanceIds(TENANT, ids);
    order.verify(mapper).archiveWorkflowRunsByInstanceIds(TENANT, ids);
    order.verify(mapper).archiveWorkflowNodeRunsByInstanceIds(TENANT, ids);
    order.verify(mapper).archiveJobExecutionLogsByInstanceIds(TENANT, ids);
    order.verify(mapper).archiveCompensationCommandsByInstanceIds(TENANT, ids);
    // FK 依赖顺序：先孙节点（job_step_instance / pipeline_step_run），中间表，最后根（job_instance）
    order.verify(mapper).deleteJobStepInstancesByInstanceIds(TENANT, ids);
    order.verify(mapper).deleteJobTasksByInstanceIds(TENANT, ids);
    order.verify(mapper).deletePipelineStepRunsByInstanceIds(TENANT, ids);
    order.verify(mapper).nullifyPipelineInstanceFileIdByInstanceIds(TENANT, ids);
    order.verify(mapper).deleteFileDispatchRecordsByInstanceIds(TENANT, ids);
    order.verify(mapper).deletePipelineInstancesByInstanceIds(TENANT, ids);
    // execution_log 必须早于 job_partition：V119 之前 job_execution_log.job_partition_id 无级联
    order.verify(mapper).deleteJobExecutionLogsByInstanceIds(TENANT, ids);
    order.verify(mapper).deleteJobPartitionsByInstanceIds(TENANT, ids);
    order.verify(mapper).deleteWorkflowNodeRunsByInstanceIds(TENANT, ids);
    order.verify(mapper).deleteWorkflowRunsByInstanceIds(TENANT, ids);
    order.verify(mapper).deleteCompensationCommandsByInstanceIds(TENANT, ids);
    order.verify(mapper).nullifyParentInstanceIdByParentIds(TENANT, ids);
    order.verify(mapper).deleteJobInstancesByIds(TENANT, ids);
  }

  @Test
  void multiTenantBatchRoutesPerTenant() {
    props.setEnabled(true);
    props.setBatchSize(100);
    // 同一批跨两个租户：按 tenant 分组,各自路由清扫。
    when(mapper.selectArchivableInstances(any(Instant.class), anyInt()))
        .thenReturn(
            List.of(
                new ArchivableInstanceRef("ta", 1L),
                new ArchivableInstanceRef("ta", 2L),
                new ArchivableInstanceRef("tb", 9L)));
    when(mapper.deleteJobInstancesByIds(eq("ta"), eq(List.of(1L, 2L)))).thenReturn(2);
    when(mapper.deleteJobInstancesByIds(eq("tb"), eq(List.of(9L)))).thenReturn(1);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.candidates()).isEqualTo(3);
    assertThat(result.instancesDeleted()).isEqualTo(3);
    verify(mapper).deleteJobInstancesByIds("ta", List.of(1L, 2L));
    verify(mapper).deleteJobInstancesByIds("tb", List.of(9L));
  }

  @Test
  void hasMoreReturnsTrueAtBatchSize() {
    props.setEnabled(true);
    props.setBatchSize(3);
    when(mapper.selectArchivableInstances(any(Instant.class), anyInt()))
        .thenReturn(refs(TENANT, 1L, 2L, 3L));
    when(mapper.deleteJobInstancesByIds(any(), any())).thenReturn(3);

    ArchiveBatchResult result = service.archiveOnce();
    assertThat(result.hasMore(3)).isTrue();
  }

  @Test
  void retentionDaysClampedToOne() {
    props.setEnabled(true);
    props.setRetentionDays(-5);
    when(mapper.selectArchivableInstances(any(Instant.class), anyInt())).thenReturn(List.of());

    service.archiveOnce();

    org.mockito.ArgumentCaptor<Instant> cutoffCaptor =
        org.mockito.ArgumentCaptor.forClass(Instant.class);
    verify(mapper).selectArchivableInstances(cutoffCaptor.capture(), anyInt());
    Instant cutoff = cutoffCaptor.getValue();
    Instant now = BatchDateTimeSupport.utcNow();
    assertThat(cutoff).isBefore(now.minusSeconds(86_400 - 5));
    assertThat(cutoff).isAfter(now.minusSeconds(86_400 + 5));
  }
}
