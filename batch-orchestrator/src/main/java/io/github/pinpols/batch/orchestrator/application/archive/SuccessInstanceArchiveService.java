package io.github.pinpols.batch.orchestrator.application.archive;

import io.github.pinpols.batch.common.time.BatchDateTimeSupport;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import io.github.pinpols.batch.orchestrator.domain.view.ArchiveIntegrityCandidateSummary;
import io.github.pinpols.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3-3 archive 系列：SUCCESS / PARTIAL_FAILED job_instance 级联归档业务层。
 *
 * <p>同删除语义对应 {@code cleanup-success-instances.sql}，本类先把运行态树复制到 {@code archive} schema 冷表，再执行 12
 * 步级联删除。单批 {@link SuccessInstanceArchiveProperties#getBatchSize()} 个 instance id 在同一事务内完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuccessInstanceArchiveService {

  private final SuccessInstanceArchiveMapper archiveMapper;
  private final SuccessInstanceArchiveProperties properties;
  private final ArchiveIntegrityAlertService integrityAlertService;

  /** 单次归档：选 batchSize 个 instance id，级联删所有依赖。 */
  @Transactional
  public ArchiveBatchResult archiveOnce() {
    if (!properties.isEnabled()) {
      return ArchiveBatchResult.disabled();
    }
    Instant cutoff = retentionCutoff();
    reportIntegrityIssues(cutoff);
    return archiveBatch(cutoff);
  }

  /** 执行调度轮级别的全量引用诊断；后续归档批次不重复扫描积压。 */
  @Transactional(readOnly = true)
  public void reportIntegrityIssues() {
    if (!properties.isEnabled()) {
      return;
    }
    reportIntegrityIssues(retentionCutoff());
  }

  /** 单个有界归档批次，不重复运行调度轮级别的全量引用诊断。 */
  @Transactional
  public ArchiveBatchResult archiveNextBatch() {
    if (!properties.isEnabled()) {
      return ArchiveBatchResult.disabled();
    }
    Instant cutoff = retentionCutoff();
    return archiveBatch(cutoff);
  }

  private Instant retentionCutoff() {
    int retention = Math.max(1, properties.getRetentionDays());
    return BatchDateTimeSupport.utcNow().minus(Duration.ofDays(retention));
  }

  private void reportIntegrityIssues(Instant cutoff) {
    List<ArchiveIntegrityCandidateSummary> integrityIssues =
        archiveMapper.selectInvalidArchiveReferenceSummaries(cutoff);
    if (!EmptyChecks.isEmpty(Objects.requireNonNull(integrityIssues))) {
      integrityAlertService.emitSuccessInstanceArchiveIssues(cutoff, integrityIssues);
    }
  }

  private ArchiveBatchResult archiveBatch(Instant cutoff) {
    int retention = Math.max(1, properties.getRetentionDays());
    int batchSize = Math.max(1, properties.getBatchSize());
    List<Long> ids = archiveMapper.selectArchivableInstanceIds(cutoff, batchSize);
    if (ids.isEmpty()) {
      return ArchiveBatchResult.empty(cutoff);
    }
    List<Long> pipelineIds = archiveMapper.selectPipelineInstanceIdsForUpdate(ids);
    // 先复制到冷表；任一步失败都会回滚事务，避免只删热表丢历史。
    archiveMapper.selectDataQualityCheckIdsForUpdate(ids);
    long archivedQualityChecks = archiveMapper.archiveDataQualityChecksByInstanceIds(ids);
    if (archiveMapper.countDataQualityCheckArchiveMismatches(ids) > 0) {
      throw new IllegalStateException(
          "Data quality check archive copy verification failed; hot data retained");
    }
    long archivedInstances = archiveMapper.archiveJobInstancesByIds(ids);
    long archivedPartitions = archiveMapper.archiveJobPartitionsByInstanceIds(ids);
    long archivedJobTasks = archiveMapper.archiveJobTasksByInstanceIds(ids);
    long archivedStepInstances = archiveMapper.archiveJobStepInstancesByInstanceIds(ids);
    long archivedPipelineInstances = archiveMapper.archivePipelineInstancesByInstanceIds(ids);
    long archivedPipelineProgress = archivePipelineCheckpoints(pipelineIds);
    long archivedPipelineStepRuns = archiveMapper.archivePipelineStepRunsByInstanceIds(ids);
    long archivedFileRecords = archiveMapper.archiveFileRecordsByInstanceIds(ids);
    long archivedFileDispatchRecords = archiveMapper.archiveFileDispatchRecordsByInstanceIds(ids);
    long archivedWorkflowRuns = archiveMapper.archiveWorkflowRunsByInstanceIds(ids);
    long archivedWorkflowNodeRuns = archiveMapper.archiveWorkflowNodeRunsByInstanceIds(ids);
    long archivedExecutionLogs = archiveMapper.archiveJobExecutionLogsByInstanceIds(ids);
    long archivedCompensations = archiveMapper.archiveCompensationCommandsByInstanceIds(ids);

    // 归档后清除热表运行树及其告警幂等 claim，避免 claim 脱离源实例长期增长。
    // 级联清理顺序遵守 cleanup-success-instances.sql。
    // FK 依赖：job_execution_log.job_partition_id 在 V119 之前没有 ON DELETE CASCADE，必须在
    // deleteJobPartitionsByInstanceIds 之前删完执行日志，否则 partition 删除会被 FK 阻塞。
    long qualityChecks = archiveMapper.deleteDataQualityChecksByInstanceIds(ids);
    long stepInstances = archiveMapper.deleteJobStepInstancesByInstanceIds(ids);
    long jobTasks = archiveMapper.deleteJobTasksByInstanceIds(ids);
    if (!EmptyChecks.isEmpty(Objects.requireNonNull(pipelineIds))) {
      archiveMapper.deletePipelineProgressByPipelineIds(pipelineIds);
    }
    long pipelineStepRuns = archiveMapper.deletePipelineStepRunsByInstanceIds(ids);
    archiveMapper.nullifyPipelineInstanceFileIdByInstanceIds(ids);
    long fileDispatchRecords = archiveMapper.deleteFileDispatchRecordsByInstanceIds(ids);
    long pipelineInstances = archiveMapper.deletePipelineInstancesByInstanceIds(ids);
    long executionLogs = archiveMapper.deleteJobExecutionLogsByInstanceIds(ids);
    long partitions = archiveMapper.deleteJobPartitionsByInstanceIds(ids);
    long workflowNodeRuns = archiveMapper.deleteWorkflowNodeRunsByInstanceIds(ids);
    long workflowRuns = archiveMapper.deleteWorkflowRunsByInstanceIds(ids);
    long compensations = archiveMapper.deleteCompensationCommandsByInstanceIds(ids);
    long monitoringClaims = archiveMapper.deleteJobMonitoringAlertClaimsByInstanceIds(ids);
    archiveMapper.nullifyParentInstanceIdByParentIds(ids);
    long instances = archiveMapper.deleteJobInstancesByIds(ids);

    log.info(
        "success-instance archive tick: cutoff={}, retention={}d, archivedInstances={},"
            + " archivedPartitions={}, archivedStepInstances={}, archivedJobTasks={},"
            + " archivedPipelineInstances={}, archivedPipelineProgress={}, archivedPipelineStepRuns={}, archivedFileRecords={},"
            + " archivedFileDispatch={}, archivedWorkflowRuns={}, archivedWorkflowNodeRuns={},"
            + " archivedExecutionLogs={}, archivedCompensations={}, instances={}, partitions={},"
            + " stepInstances={}, jobTasks={}, pipelineInstances={}, pipelineStepRuns={},"
            + " fileDispatch={}, workflowRuns={}, workflowNodeRuns={}, executionLogs={},"
            + " compensations={}, monitoringClaims={}, archivedQualityChecks={}, qualityChecks={}",
        cutoff,
        retention,
        archivedInstances,
        archivedPartitions,
        archivedStepInstances,
        archivedJobTasks,
        archivedPipelineInstances,
        archivedPipelineProgress,
        archivedPipelineStepRuns,
        archivedFileRecords,
        archivedFileDispatchRecords,
        archivedWorkflowRuns,
        archivedWorkflowNodeRuns,
        archivedExecutionLogs,
        archivedCompensations,
        instances,
        partitions,
        stepInstances,
        jobTasks,
        pipelineInstances,
        pipelineStepRuns,
        fileDispatchRecords,
        workflowRuns,
        workflowNodeRuns,
        executionLogs,
        compensations,
        monitoringClaims,
        archivedQualityChecks,
        qualityChecks);
    return new ArchiveBatchResult(true, cutoff, ids.size(), (int) instances);
  }

  private int archivePipelineCheckpoints(List<Long> pipelineIds) {
    if (EmptyChecks.isEmpty(Objects.requireNonNull(pipelineIds))) {
      return 0;
    }
    archiveMapper.selectPipelineProgressIdsForUpdate(pipelineIds);
    int archived = archiveMapper.archivePipelineProgressByPipelineIds(pipelineIds);
    if (archiveMapper.countPipelineCheckpointArchiveMismatches(pipelineIds) > 0) {
      throw new IllegalStateException(
          "Pipeline checkpoint archive copy verification failed; hot data retained");
    }
    return archived;
  }

  public record ArchiveBatchResult(
      boolean executed, Instant cutoff, int candidates, int instancesDeleted) {

    public static ArchiveBatchResult disabled() {
      return new ArchiveBatchResult(false, null, 0, 0);
    }

    public static ArchiveBatchResult empty(Instant cutoff) {
      return new ArchiveBatchResult(true, cutoff, 0, 0);
    }

    public boolean hasMore(int batchSize) {
      return executed && candidates >= batchSize;
    }
  }
}
