package io.github.pinpols.batch.orchestrator.application.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.orchestrator.application.archive.SuccessInstanceArchiveService.ArchiveBatchResult;
import io.github.pinpols.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import io.github.pinpols.batch.orchestrator.domain.view.ArchiveIntegrityCandidateSummary;
import io.github.pinpols.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

@DisplayName("成功实例归档服务: 开关短路, 候选清理顺序与保留期下限口径")
class SuccessInstanceArchiveServiceTest {

  private SuccessInstanceArchiveMapper mapper;
  private SuccessInstanceArchiveProperties props;
  private ArchiveIntegrityAlertService integrityAlertService;
  private SuccessInstanceArchiveService service;

  @BeforeEach
  void setUp() {
    mapper = mock(SuccessInstanceArchiveMapper.class);
    props = new SuccessInstanceArchiveProperties();
    integrityAlertService = mock(ArchiveIntegrityAlertService.class);
    when(mapper.selectInvalidArchiveReferenceSummaries(any(Instant.class))).thenReturn(List.of());
    service = new SuccessInstanceArchiveService(mapper, props, integrityAlertService);
  }

  @Test
  @DisplayName("归档开关关闭时直接返回未执行, 且不查询候选")
  void shouldShortCircuit_whenArchiveDisabled() {
    props.setEnabled(false);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isFalse();
    verify(mapper, never()).selectArchivableInstanceIds(any(Instant.class), anyInt());
    service.reportIntegrityIssues();
    service.archiveNextBatch();
    verify(mapper, never()).disableJitForCurrentTransaction();
  }

  @Test
  @DisplayName("每个归档事务在查询前局部关闭JIT，不改变候选或诊断语义")
  void shouldDisableJitBeforeQueriesForEachArchiveEntry() {
    props.setEnabled(true);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(List.of());

    service.archiveOnce();
    service.reportIntegrityIssues();
    service.archiveNextBatch();

    InOrder order = Mockito.inOrder(mapper);
    order.verify(mapper).disableJitForCurrentTransaction();
    order.verify(mapper).selectInvalidArchiveReferenceSummaries(any(Instant.class));
    order.verify(mapper).selectArchivableInstanceIds(any(Instant.class), anyInt());
    order.verify(mapper).disableJitForCurrentTransaction();
    order.verify(mapper).selectInvalidArchiveReferenceSummaries(any(Instant.class));
    order.verify(mapper).disableJitForCurrentTransaction();
    order.verify(mapper).selectArchivableInstanceIds(any(Instant.class), anyInt());
    order.verifyNoMoreInteractions();
  }

  @Test
  @DisplayName("没有候选实例时返回已执行但计数为零, 不产生删除")
  void shouldReturnEmptyResult_whenNoCandidates() {
    props.setEnabled(true);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(List.of());

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isTrue();
    assertThat(result.candidates()).isZero();
    verify(mapper, never()).deleteJobInstancesByIds(any());
  }

  @Test
  @DisplayName("检测到运行树引用归属错误时写入持久告警")
  void shouldPersistAlertWhenInvalidArchiveReferenceExists() {
    props.setEnabled(true);
    List<ArchiveIntegrityCandidateSummary> issues =
        List.of(new ArchiveIntegrityCandidateSummary("tenant-a", 2L, 42L));
    when(mapper.selectInvalidArchiveReferenceSummaries(any(Instant.class))).thenReturn(issues);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(List.of());

    service.archiveOnce();

    verify(mapper).selectInvalidArchiveReferenceSummaries(any(Instant.class));
    verify(integrityAlertService).emitSuccessInstanceArchiveIssues(any(Instant.class), eq(issues));
    verify(mapper, never()).deleteJobInstancesByIds(any());
  }

  @Test
  @DisplayName("调度轮引用诊断只运行一次,后续有界批次不重复扫描积压")
  void shouldRunIntegrityDiagnosticOnceForMultipleArchiveBatches() {
    props.setEnabled(true);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(List.of());

    service.reportIntegrityIssues();
    service.archiveNextBatch();
    service.archiveNextBatch();

    verify(mapper, times(1)).selectInvalidArchiveReferenceSummaries(any(Instant.class));
    verify(mapper, times(2)).selectArchivableInstanceIds(any(Instant.class), anyInt());
  }

  @Test
  @DisplayName("有候选实例时先归档各冷表, 再按外键依赖顺序级联删除")
  void shouldArchiveColdTablesBeforeCascadeDelete_whenCandidatesExist() {
    props.setEnabled(true);
    props.setBatchSize(100);
    List<Long> ids = List.of(1L, 2L, 3L);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(ids);
    List<Long> pipelineIds = List.of(11L, 22L);
    when(mapper.selectPipelineInstanceIdsForUpdate(ids)).thenReturn(pipelineIds);
    when(mapper.archiveJobInstancesByIds(ids)).thenReturn(3);
    when(mapper.deleteJobInstancesByIds(ids)).thenReturn(3);

    ArchiveBatchResult result = service.archiveOnce();

    assertThat(result.executed()).isTrue();
    assertThat(result.instancesDeleted()).isEqualTo(3);

    InOrder order = Mockito.inOrder(mapper);
    order.verify(mapper).selectPipelineInstanceIdsForUpdate(ids);
    order.verify(mapper).lockRuntimeTreeRows(ids);
    order.verify(mapper).archiveJobInstancesByIds(ids);
    order.verify(mapper).countJobInstanceArchiveMismatches(ids);
    order.verify(mapper).archiveJobPartitionsByInstanceIds(ids);
    order.verify(mapper).archiveJobTasksByInstanceIds(ids);
    order.verify(mapper).countJobTaskArchiveMismatches(ids);
    order.verify(mapper).archiveJobStepInstancesByInstanceIds(ids);
    order.verify(mapper).archivePipelineInstancesByInstanceIds(ids);
    order.verify(mapper).selectPipelineProgressIdsForUpdate(pipelineIds);
    order.verify(mapper).archivePipelineProgressByPipelineIds(pipelineIds);
    order.verify(mapper).countPipelineCheckpointArchiveMismatches(pipelineIds);
    order.verify(mapper).archivePipelineStepRunsByInstanceIds(ids);
    order.verify(mapper).archiveFileRecordsByInstanceIds(ids);
    order.verify(mapper).archiveFileDispatchRecordsByInstanceIds(ids);
    order.verify(mapper).archiveWorkflowRunsByInstanceIds(ids);
    order.verify(mapper).archiveWorkflowNodeRunsByInstanceIds(ids);
    order.verify(mapper).archiveJobExecutionLogsByInstanceIds(ids);
    order.verify(mapper).archiveCompensationCommandsByInstanceIds(ids);
    order.verify(mapper).countRuntimeTreeArchiveMismatches(ids);
    // FK 依赖顺序：先孙节点（job_step_instance / pipeline_step_run），中间表，最后根（job_instance）
    order.verify(mapper).deleteJobStepInstancesByInstanceIds(ids);
    order.verify(mapper).deleteJobTasksByInstanceIds(ids);
    order.verify(mapper).deletePipelineProgressByPipelineIds(pipelineIds);
    order.verify(mapper).deletePipelineStepRunsByInstanceIds(ids);
    order.verify(mapper).nullifyPipelineInstanceFileIdByInstanceIds(ids);
    order.verify(mapper).deleteFileDispatchRecordsByInstanceIds(ids);
    order.verify(mapper).deletePipelineInstancesByInstanceIds(ids);
    // execution_log 必须早于 job_partition：V119 之前 job_execution_log.job_partition_id 无级联
    order.verify(mapper).deleteJobExecutionLogsByInstanceIds(ids);
    order.verify(mapper).deleteJobPartitionsByInstanceIds(ids);
    order.verify(mapper).deleteWorkflowNodeRunsByInstanceIds(ids);
    order.verify(mapper).deleteWorkflowRunsByInstanceIds(ids);
    order.verify(mapper).deleteCompensationCommandsByInstanceIds(ids);
    order.verify(mapper).nullifyParentInstanceIdByParentIds(ids);
    order.verify(mapper).deleteJobInstancesByIds(ids);
  }

  @Test
  @DisplayName("位点冷副本冲突时不删除热位点或父运行树")
  void shouldRetainHotTreeWhenCheckpointCopyMismatches() {
    List<Long> ids = List.of(1L);
    List<Long> pipelineIds = List.of(11L);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(ids);
    when(mapper.selectPipelineInstanceIdsForUpdate(ids)).thenReturn(pipelineIds);
    when(mapper.countPipelineCheckpointArchiveMismatches(pipelineIds)).thenReturn(1L);

    assertThatThrownBy(service::archiveOnce)
        .hasMessageContaining("Pipeline checkpoint archive copy verification failed");
    verify(mapper, never()).deletePipelineProgressByPipelineIds(any());
    verify(mapper, never()).deletePipelineInstancesByInstanceIds(any());
    verify(mapper, never()).deleteJobInstancesByIds(any());
  }

  @Test
  @DisplayName("候选数量达到批量大小时判定还有更多待归档")
  void shouldReportMore_whenCandidatesEqualBatchSize() {
    props.setEnabled(true);
    props.setBatchSize(3);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt()))
        .thenReturn(List.of(1L, 2L, 3L));
    when(mapper.deleteJobInstancesByIds(any())).thenReturn(3);

    ArchiveBatchResult result = service.archiveOnce();
    assertThat(result.hasMore(3)).isTrue();
  }

  @Test
  @DisplayName("保留天数非正时收敛为一天, 归档截止时间相应前移")
  void shouldClampRetentionToOneDay_whenRetentionNotPositive() {
    props.setEnabled(true);
    props.setRetentionDays(-5);
    when(mapper.selectArchivableInstanceIds(any(Instant.class), anyInt())).thenReturn(List.of());

    service.archiveOnce();

    ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
    verify(mapper).selectArchivableInstanceIds(cutoffCaptor.capture(), anyInt());
    Instant cutoff = cutoffCaptor.getValue();
    Instant now = BatchDateTimeSupport.utcNow();
    assertThat(cutoff).isBefore(now.minusSeconds(86_400 - 5)).isAfter(now.minusSeconds(86_400 + 5));
  }
}
