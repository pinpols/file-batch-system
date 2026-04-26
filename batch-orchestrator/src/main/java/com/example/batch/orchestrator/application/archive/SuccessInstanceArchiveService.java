package com.example.batch.orchestrator.application.archive;

import com.example.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import com.example.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P3-3 archive 系列：SUCCESS / PARTIAL_FAILED job_instance 级联归档业务层。
 *
 * <p>同删除语义对应 {@code cleanup-success-instances.sql}，本类先把运行态树复制到 {@code archive}
 * schema 冷表，再执行 12 步级联删除。单批 {@link SuccessInstanceArchiveProperties#getBatchSize()} 个
 * instance id 在同一事务内完成。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuccessInstanceArchiveService {

  private final SuccessInstanceArchiveMapper archiveMapper;
  private final SuccessInstanceArchiveProperties properties;

  /** 单次归档：选 batchSize 个 instance id，级联删所有依赖。 */
  @Transactional
  public ArchiveBatchResult archiveOnce() {
    if (!properties.isEnabled()) {
      return ArchiveBatchResult.disabled();
    }
    int retention = Math.max(1, properties.getRetentionDays());
    int batchSize = Math.max(1, properties.getBatchSize());
    Instant cutoff = Instant.now().minus(Duration.ofDays(retention));
    List<Long> ids = archiveMapper.selectArchivableInstanceIds(cutoff, batchSize);
    if (ids.isEmpty()) {
      return ArchiveBatchResult.empty(cutoff);
    }
    // 先复制到冷表；任一步失败都会回滚事务，避免只删热表丢历史。
    long archivedInstances = archiveMapper.archiveJobInstancesByIds(ids);
    long archivedPartitions = archiveMapper.archiveJobPartitionsByInstanceIds(ids);
    long archivedJobTasks = archiveMapper.archiveJobTasksByInstanceIds(ids);
    long archivedStepInstances = archiveMapper.archiveJobStepInstancesByInstanceIds(ids);
    long archivedPipelineInstances = archiveMapper.archivePipelineInstancesByInstanceIds(ids);
    long archivedPipelineStepRuns = archiveMapper.archivePipelineStepRunsByInstanceIds(ids);
    long archivedFileDispatchRecords = archiveMapper.archiveFileDispatchRecordsByInstanceIds(ids);
    long archivedWorkflowRuns = archiveMapper.archiveWorkflowRunsByInstanceIds(ids);
    long archivedWorkflowNodeRuns = archiveMapper.archiveWorkflowNodeRunsByInstanceIds(ids);
    long archivedExecutionLogs = archiveMapper.archiveJobExecutionLogsByInstanceIds(ids);
    long archivedCompensations = archiveMapper.archiveCompensationCommandsByInstanceIds(ids);

    // 12 步级联删（顺序遵守 cleanup-success-instances.sql）
    long stepInstances = archiveMapper.deleteJobStepInstancesByInstanceIds(ids);
    long jobTasks = archiveMapper.deleteJobTasksByInstanceIds(ids);
    long pipelineStepRuns = archiveMapper.deletePipelineStepRunsByInstanceIds(ids);
    archiveMapper.nullifyPipelineInstanceFileIdByInstanceIds(ids);
    long fileDispatchRecords = archiveMapper.deleteFileDispatchRecordsByInstanceIds(ids);
    long pipelineInstances = archiveMapper.deletePipelineInstancesByInstanceIds(ids);
    long partitions = archiveMapper.deleteJobPartitionsByInstanceIds(ids);
    long workflowNodeRuns = archiveMapper.deleteWorkflowNodeRunsByInstanceIds(ids);
    long workflowRuns = archiveMapper.deleteWorkflowRunsByInstanceIds(ids);
    long executionLogs = archiveMapper.deleteJobExecutionLogsByInstanceIds(ids);
    long compensations = archiveMapper.deleteCompensationCommandsByInstanceIds(ids);
    archiveMapper.nullifyParentInstanceIdByParentIds(ids);
    long instances = archiveMapper.deleteJobInstancesByIds(ids);

    log.info(
        "success-instance archive tick: cutoff={}, retention={}d, archivedInstances={},"
            + " archivedPartitions={}, archivedStepInstances={}, archivedJobTasks={},"
            + " archivedPipelineInstances={}, archivedPipelineStepRuns={}, archivedFileDispatch={},"
            + " archivedWorkflowRuns={}, archivedWorkflowNodeRuns={}, archivedExecutionLogs={},"
            + " archivedCompensations={}, instances={}, partitions={}, stepInstances={}, jobTasks={},"
            + " pipelineInstances={}, pipelineStepRuns={}, fileDispatch={}, workflowRuns={},"
            + " workflowNodeRuns={}, executionLogs={}, compensations={}",
        cutoff,
        retention,
        archivedInstances,
        archivedPartitions,
        archivedStepInstances,
        archivedJobTasks,
        archivedPipelineInstances,
        archivedPipelineStepRuns,
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
        compensations);
    return new ArchiveBatchResult(true, cutoff, ids.size(), (int) instances);
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
