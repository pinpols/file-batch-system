package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.value.ArchivableInstanceRef;
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

  /**
   * 选 SUCCESS / PARTIAL_FAILED 且 finished_at 早于 cutoff 的可归档实例引用 {@code (tenantId, id)}（带
   * limit）。Citus:返回带租户的引用,供 service 按 tenantId 分组后逐租户路由清扫。
   */
  List<ArchivableInstanceRef> selectArchivableInstances(
      @Param("cutoff") Instant cutoff, @Param("limit") int limit);

  // ── 冷表归档（按 tenantId 路由 + instanceIds 列表，先复制后删除）──────────────────────

  int archiveJobInstancesByIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archiveJobPartitionsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archiveJobTasksByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archiveJobStepInstancesByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archivePipelineInstancesByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archivePipelineStepRunsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archiveFileDispatchRecordsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archiveWorkflowRunsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archiveWorkflowNodeRunsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archiveJobExecutionLogsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int archiveCompensationCommandsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  // ── 级联删（按 tenantId 路由 + instanceIds 列表）──────────────────────────────

  int deleteJobStepInstancesByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int deleteJobTasksByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int deletePipelineStepRunsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int nullifyPipelineInstanceFileIdByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int deleteFileDispatchRecordsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int deletePipelineInstancesByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int deleteJobPartitionsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int deleteWorkflowNodeRunsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int deleteWorkflowRunsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int deleteJobExecutionLogsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int deleteCompensationCommandsByInstanceIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);

  int nullifyParentInstanceIdByParentIds(
      @Param("tenantId") String tenantId, @Param("parentIds") List<Long> parentIds);

  int deleteJobInstancesByIds(
      @Param("tenantId") String tenantId, @Param("instanceIds") List<Long> instanceIds);
}
