package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.view.ArchiveIntegrityCandidateSummary;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * P3-3 archive 系列：终态 job_instance 级联归档专用 mapper。
 *
 * <p>归档服务按实例 ID 批量复制冷表并清理热表，具体事务与依赖顺序由
 * {@code SuccessInstanceArchiveService} 控制。独立的 {@code cleanup-success-instances.sql}
 * 仅清理成功及部分失败实例，不执行冷表复制，两者不是同一操作。
 */
public interface SuccessInstanceArchiveMapper {

  /** 仅在当前归档事务关闭 JIT，避免分区引用诊断的编译成本压倒有界查询。 */
  String disableJitForCurrentTransaction();

  /** 按租户汇总到期终态实例中存在无效归档引用的候选。 */
  List<ArchiveIntegrityCandidateSummary> selectInvalidArchiveReferenceSummaries(
      @Param("cutoff") Instant cutoff);

  List<Long> selectArchivableInstanceIds(
      @Param("cutoff") Instant cutoff, @Param("limit") int limit);

  /** 复制前锁定本批现有运行树子行，防止内容核对后被并发改写。 */
  long lockRuntimeTreeRows(@Param("instanceIds") List<Long> instanceIds);

  /** 固定本批 Pipeline 集合并按 ID 持父行锁，阻止新位点绕过本批复制。 */
  List<Long> selectPipelineInstanceIdsForUpdate(@Param("instanceIds") List<Long> instanceIds);

  /** 父行持锁后锁定已有位点，保护复制与删除之间的并发更新。 */
  List<Long> selectPipelineProgressIdsForUpdate(@Param("pipelineIds") List<Long> pipelineIds);

  int archivePipelineProgressByPipelineIds(@Param("pipelineIds") List<Long> pipelineIds);

  long countPipelineCheckpointArchiveMismatches(@Param("pipelineIds") List<Long> pipelineIds);

  int deletePipelineProgressByPipelineIds(@Param("pipelineIds") List<Long> pipelineIds);

  // ── 冷表归档（按 instanceIds 列表，先复制后删除）──────────────────────

  int archiveJobInstancesByIds(@Param("instanceIds") List<Long> instanceIds);

  long countJobInstanceArchiveMismatches(@Param("instanceIds") List<Long> instanceIds);

  int archiveJobPartitionsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveJobTasksByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  long countJobTaskArchiveMismatches(@Param("instanceIds") List<Long> instanceIds);

  int archiveJobStepInstancesByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archivePipelineInstancesByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archivePipelineStepRunsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveFileRecordsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveFileDispatchRecordsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveWorkflowRunsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveWorkflowNodeRunsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveJobExecutionLogsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveCompensationCommandsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  long countRuntimeTreeArchiveMismatches(@Param("instanceIds") List<Long> instanceIds);

  List<Long> selectDataQualityCheckIdsForUpdate(@Param("instanceIds") List<Long> instanceIds);

  int archiveDataQualityChecksByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int countDataQualityCheckArchiveMismatches(@Param("instanceIds") List<Long> instanceIds);

  int deleteDataQualityChecksByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  // ── 级联删（按 instanceIds 列表）──────────────────────────────

  int deleteJobStepInstancesByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deleteJobTasksByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deletePipelineStepRunsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int nullifyPipelineInstanceFileIdByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deleteFileDispatchRecordsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deletePipelineInstancesByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deleteJobPartitionsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deleteWorkflowNodeRunsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deleteWorkflowRunsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deleteJobExecutionLogsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deleteCompensationCommandsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int deleteJobMonitoringAlertClaimsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int nullifyParentInstanceIdByParentIds(@Param("parentIds") List<Long> parentIds);

  int deleteJobInstancesByIds(@Param("instanceIds") List<Long> instanceIds);
}
