package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.WorkflowRunMarkRunningParam;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowRunActiveByTenantWorkflowBizDateQuery;


import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateWorkflowRunStatusParam;
import io.github.pinpols.batch.orchestrator.domain.query.WorkflowRunQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowRunMapper {

  int insert(WorkflowRunEntity entity);

  /** 仅活跃名额冲突返回零；其他唯一键、外键及检查约束异常继续向调用方传播。 */
  int insertIfActiveSlotFree(WorkflowRunEntity entity);

  WorkflowRunEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  WorkflowRunEntity selectByTenantAndIdForUpdate(
      @Param("tenantId") String tenantId, @Param("id") Long id);

  WorkflowRunEntity selectByTenantAndTriggerRequestId(
      @Param("tenantId") String tenantId, @Param("triggerRequestId") Long triggerRequestId);

  WorkflowRunEntity selectActiveByTenantWorkflowBizDate(
      WorkflowRunActiveByTenantWorkflowBizDateQuery parameters);

  /** ADR-018 reconciler：跨租户扫 WAITING_DEPENDENCY 时按 id 直查（id 全局唯一 BIGSERIAL）。 */
  WorkflowRunEntity selectByIdAnyTenant(@Param("id") Long id);

  WorkflowRunEntity selectByRelatedJobInstanceId(
      @Param("tenantId") String tenantId, @Param("relatedJobInstanceId") Long relatedJobInstanceId);

  /** 通过 JOB 节点显式执行关联解析其所属的直接 Workflow 运行。 */
  WorkflowRunEntity selectByNodeJobInstanceId(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  /** 查找捕获指定 Job 版本的最新不可变 Workflow 执行快照。 */
  WorkflowRunEntity selectLatestJobDefinitionSnapshot(
      @Param("tenantId") String tenantId,
      @Param("jobDefinitionId") Long jobDefinitionId,
      @Param("jobCode") String jobCode,
      @Param("jobDefinitionVersion") Integer jobDefinitionVersion);

  List<WorkflowRunEntity> selectByQuery(WorkflowRunQuery query);

  /**
   * 更新 workflow_run 状态/当前节点/完成时间。
   *
   * <p>{@link UpdateWorkflowRunStatusParam#getExpectedStatuses()} 非空时附加 {@code and run_status in
   * (...)} 守护，命中 0 行说明前态不在期望集（cancel/terminate 与 task outcome 抢占等）；调用方按返回行数判定是否冲突。
   */
  int updateStatus(UpdateWorkflowRunStatusParam param);

  int markRunning(
      WorkflowRunMarkRunningParam parameters);

  /** P3-3 archive: 选出终结态、finished_at 早于 cutoff 的 workflow_run id（带 limit 防长事务）。 */
  List<Long> selectArchivableIds(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

  /**
   * 选 RUNNING 中且 {@code updated_at} 早于 {@code stuckBefore} 的 workflow_run（疑似 stuck，等下游
   * 推进信号永远没回来）。配合 {@link WorkflowNodeRunMapper#selectByWorkflowRunId} 判定是否所有 node 已终态后做回退
   * finalize。详见 {@code WorkflowRunStuckReconciler}。
   */
  List<WorkflowRunEntity> selectStuckRunningCandidates(
      @Param("stuckBefore") Instant stuckBefore, @Param("limit") int limit);

  /** P3-3 archive: 按 id 列表删除 workflow_node_run（FK 子表），返回删除行数。 */
  int deleteNodeRunsByWorkflowRunIds(@Param("ids") List<Long> workflowRunIds);

  /** P3-3 archive: 按 id 列表删除 workflow_run，返回删除行数。 */
  int deleteByIds(@Param("ids") List<Long> ids);

  /**
   * A3 fix: 把 workflow_run 行 insert 到 archive.workflow_run_archive(insert-before-delete 保数据 不丢)。on
   * conflict do nothing 防重放幂等。
   */
  int archiveWorkflowRunsByIds(@Param("ids") List<Long> ids);

  /** A3 fix: 把 workflow_node_run 子行 insert 到 archive.workflow_node_run_archive。 */
  int archiveWorkflowNodeRunsByWorkflowRunIds(@Param("ids") List<Long> workflowRunIds);

  /** 复制后核对根及节点的完整行，非零表示缺失或冲突冷记录，禁止删除热数据。 */
  long countArchiveCopyMismatches(@Param("ids") List<Long> workflowRunIds);
}
