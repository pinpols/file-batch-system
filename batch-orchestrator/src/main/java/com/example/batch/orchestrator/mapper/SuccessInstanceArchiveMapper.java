package com.example.batch.orchestrator.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * P3-3 archive 系列：SUCCESS / PARTIAL_FAILED job_instance 级联归档专用 mapper。
 *
 * <p>所有删除按 job_instance.id 列表批处理，对应 {@code cleanup-success-instances.sql} 同语义。 删除顺序（FK 依赖）：
 *
 * <ol>
 *   <li>job_step_instance（依赖 job_partition）
 *   <li>job_task → job_instance
 *   <li>pipeline_step_run → pipeline_instance
 *   <li>pipeline_instance.file_id = NULL（解 FK 引用）
 *   <li>file_dispatch_record → pipeline_instance
 *   <li>pipeline_instance → job_instance
 *   <li>job_partition → job_instance
 *   <li>workflow_node_run → workflow_run
 *   <li>workflow_run → job_instance
 *   <li>job_execution_log → job_instance
 *   <li>compensation_command → job_instance
 *   <li>job_instance.parent_instance_id NULL（解自引用 FK）
 *   <li>job_instance（根删）
 * </ol>
 */
public interface SuccessInstanceArchiveMapper {

  /** 选 SUCCESS / PARTIAL_FAILED 且 finished_at 早于 cutoff 的 instance id（带 limit）。 */
  List<Long> selectArchivableInstanceIds(
      @Param("cutoff") Instant cutoff, @Param("limit") int limit);

  // ── 冷表归档（按 instanceIds 列表，先复制后删除）──────────────────────

  int archiveJobInstancesByIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveJobPartitionsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveJobTasksByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveJobStepInstancesByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archivePipelineInstancesByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archivePipelineStepRunsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveFileDispatchRecordsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveWorkflowRunsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveWorkflowNodeRunsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveJobExecutionLogsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

  int archiveCompensationCommandsByInstanceIds(@Param("instanceIds") List<Long> instanceIds);

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

  int nullifyParentInstanceIdByParentIds(@Param("parentIds") List<Long> parentIds);

  int deleteJobInstancesByIds(@Param("instanceIds") List<Long> instanceIds);
}
