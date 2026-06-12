package com.example.batch.orchestrator.application.archive;

import com.example.batch.common.time.BatchDateTimeSupport;
import com.example.batch.orchestrator.config.SuccessInstanceArchiveProperties;
import com.example.batch.orchestrator.domain.value.ArchivableInstanceRef;
import com.example.batch.orchestrator.mapper.SuccessInstanceArchiveMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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

  /**
   * 单次归档：选 batchSize 个可归档实例（带租户），按 tenant 分组后逐租户级联归档+删除。
   *
   * <p>Citus:同一批可能跨多个租户/分片;原全局 id 列表跨表关联会触发非共址 complex join。改为按 tenantId
   * 分组,每租户的归档/删除语句都路由到单分片、子查询在分布列(tenant_id)上共址。全部租户仍在 同一 {@link Transactional}
   * 内完成,保留"先复制后删除、任一步失败整体回滚"的原子语义。
   */
  @Transactional
  public ArchiveBatchResult archiveOnce() {
    if (!properties.isEnabled()) {
      return ArchiveBatchResult.disabled();
    }
    int retention = Math.max(1, properties.getRetentionDays());
    int batchSize = Math.max(1, properties.getBatchSize());
    Instant cutoff = BatchDateTimeSupport.utcNow().minus(Duration.ofDays(retention));
    List<ArchivableInstanceRef> refs = archiveMapper.selectArchivableInstances(cutoff, batchSize);
    if (refs.isEmpty()) {
      return ArchiveBatchResult.empty(cutoff);
    }
    // 保持选取顺序(tenant_id, id)分组:LinkedHashMap 保证租户处理顺序稳定。
    Map<String, List<Long>> idsByTenant =
        refs.stream()
            .collect(
                Collectors.groupingBy(
                    ArchivableInstanceRef::tenantId,
                    LinkedHashMap::new,
                    Collectors.mapping(ArchivableInstanceRef::id, Collectors.toList())));

    int totalCandidates = refs.size();
    int totalInstancesDeleted = 0;
    for (Map.Entry<String, List<Long>> entry : idsByTenant.entrySet()) {
      totalInstancesDeleted +=
          archiveTenantBatch(cutoff, retention, entry.getKey(), entry.getValue());
    }
    return new ArchiveBatchResult(true, cutoff, totalCandidates, totalInstancesDeleted);
  }

  /** 单租户归档+级联删除(所有语句带 tenant_id 路由,落单分片)。返回该租户删除的 instance 数。 */
  private int archiveTenantBatch(Instant cutoff, int retention, String tenantId, List<Long> ids) {
    // 先复制到冷表；任一步失败都会回滚事务，避免只删热表丢历史。
    long archivedInstances = archiveMapper.archiveJobInstancesByIds(tenantId, ids);
    long archivedPartitions = archiveMapper.archiveJobPartitionsByInstanceIds(tenantId, ids);
    long archivedJobTasks = archiveMapper.archiveJobTasksByInstanceIds(tenantId, ids);
    long archivedStepInstances = archiveMapper.archiveJobStepInstancesByInstanceIds(tenantId, ids);
    long archivedPipelineInstances =
        archiveMapper.archivePipelineInstancesByInstanceIds(tenantId, ids);
    long archivedPipelineStepRuns =
        archiveMapper.archivePipelineStepRunsByInstanceIds(tenantId, ids);
    long archivedFileDispatchRecords =
        archiveMapper.archiveFileDispatchRecordsByInstanceIds(tenantId, ids);
    long archivedWorkflowRuns = archiveMapper.archiveWorkflowRunsByInstanceIds(tenantId, ids);
    long archivedWorkflowNodeRuns =
        archiveMapper.archiveWorkflowNodeRunsByInstanceIds(tenantId, ids);
    long archivedExecutionLogs = archiveMapper.archiveJobExecutionLogsByInstanceIds(tenantId, ids);
    long archivedCompensations =
        archiveMapper.archiveCompensationCommandsByInstanceIds(tenantId, ids);

    // 12 步级联删（顺序遵守 cleanup-success-instances.sql）
    // FK 依赖：job_execution_log.job_partition_id 在 V119 之前没有 ON DELETE CASCADE，必须在
    // deleteJobPartitionsByInstanceIds 之前删完执行日志，否则 partition 删除会被 FK 阻塞。
    long stepInstances = archiveMapper.deleteJobStepInstancesByInstanceIds(tenantId, ids);
    long jobTasks = archiveMapper.deleteJobTasksByInstanceIds(tenantId, ids);
    long pipelineStepRuns = archiveMapper.deletePipelineStepRunsByInstanceIds(tenantId, ids);
    archiveMapper.nullifyPipelineInstanceFileIdByInstanceIds(tenantId, ids);
    long fileDispatchRecords = archiveMapper.deleteFileDispatchRecordsByInstanceIds(tenantId, ids);
    long pipelineInstances = archiveMapper.deletePipelineInstancesByInstanceIds(tenantId, ids);
    long executionLogs = archiveMapper.deleteJobExecutionLogsByInstanceIds(tenantId, ids);
    long partitions = archiveMapper.deleteJobPartitionsByInstanceIds(tenantId, ids);
    long workflowNodeRuns = archiveMapper.deleteWorkflowNodeRunsByInstanceIds(tenantId, ids);
    long workflowRuns = archiveMapper.deleteWorkflowRunsByInstanceIds(tenantId, ids);
    long compensations = archiveMapper.deleteCompensationCommandsByInstanceIds(tenantId, ids);
    archiveMapper.nullifyParentInstanceIdByParentIds(tenantId, ids);
    long instances = archiveMapper.deleteJobInstancesByIds(tenantId, ids);

    log.info(
        "success-instance archive tick: tenantId={}, cutoff={}, retention={}d,"
            + " archivedInstances={}, archivedPartitions={}, archivedStepInstances={},"
            + " archivedJobTasks={}, archivedPipelineInstances={}, archivedPipelineStepRuns={},"
            + " archivedFileDispatch={}, archivedWorkflowRuns={}, archivedWorkflowNodeRuns={},"
            + " archivedExecutionLogs={}, archivedCompensations={}, instances={}, partitions={},"
            + " stepInstances={}, jobTasks={}, pipelineInstances={}, pipelineStepRuns={},"
            + " fileDispatch={}, workflowRuns={}, workflowNodeRuns={}, executionLogs={},"
            + " compensations={}",
        tenantId,
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
    return (int) instances;
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
