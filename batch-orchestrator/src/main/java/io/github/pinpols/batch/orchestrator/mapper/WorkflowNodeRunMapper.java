package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.param.WorkflowNodeRunUpdateSensorProbeStateParam;


import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowNodeRunMapper {

  int insert(WorkflowNodeRunEntity entity);

  /** 领取唯一运行序号；并发首次激活不应转化为错误。 */
  int insertIfAbsent(WorkflowNodeRunEntity entity);

  int attachJobInstance(
      @Param("tenantId") String tenantId,
      @Param("nodeRunId") Long nodeRunId,
      @Param("jobInstanceId") Long jobInstanceId);

  WorkflowNodeRunEntity selectLatestByWorkflowRunIdAndNodeCode(
      @Param("workflowRunId") Long workflowRunId, @Param("nodeCode") String nodeCode);

  /** 精确锁定属于给定租户和 RootRun 的节点尝试，供失败节点重放校验来源身份。 */
  WorkflowNodeRunEntity selectByTenantRunAndIdForUpdate(
      @Param("tenantId") String tenantId,
      @Param("workflowRunId") Long workflowRunId,
      @Param("nodeRunId") Long nodeRunId);

  /**
   * S3 / R3-P1-2：按 (workflowRunId, nodeCodes IN) 批量取每个 node_code 的最新 run_seq 行， 消除
   * isNodeReadyForDispatch / canNeverFire 循环里的 N+1。 实现：postgres DISTINCT ON (node_code) ORDER BY
   * node_code, run_seq DESC。 调用方需保证 nodeCodes 非空。
   */
  List<WorkflowNodeRunEntity> selectLatestByWorkflowRunIdAndNodeCodesIn(
      @Param("workflowRunId") Long workflowRunId, @Param("nodeCodes") Collection<String> nodeCodes);

  // C-1/C-3: pessimistic lock to serialize concurrent recordNodeRunFinish /
  // isNodeAlreadyActivated
  WorkflowNodeRunEntity selectLatestForUpdate(
      @Param("workflowRunId") Long workflowRunId, @Param("nodeCode") String nodeCode);

  int updateStatus(UpdateNodeRunStatusParam param);

  List<WorkflowNodeRunEntity> selectByWorkflowRunId(@Param("workflowRunId") Long workflowRunId);

  /** ADR-018 Stage 5/7：扫 WAITING_DEPENDENCY 节点供 reconciler 重试 / 超时治理。 */
  List<WorkflowNodeRunEntity> selectByNodeStatus(
      @Param("nodeStatus") String nodeStatus, @Param("limit") int limit);

  /** 扫描每个节点的最新运行尝试，避免再次处理已由更高 run_seq 替代的等待历史。 */
  List<WorkflowNodeRunEntity> selectLatestByNodeStatus(
      @Param("nodeStatus") String nodeStatus, @Param("limit") int limit);

  /** ADR-028 S3：扫到期 WAIT 节点（node_type=WAIT, status=RUNNING, next_probe_at &le; now）。 */
  List<WorkflowNodeRunEntity> selectDueWaitNodes(
      @Param("now") Instant now, @Param("limit") int limit);

  /** 原子认领仍处于到期 RUNNING 状态的 WAIT 行；受影响行数为零表示其他扫描者已推进。 */
  int claimDueWaitNode(@Param("id") Long id, @Param("now") Instant now);

  /** ADR-028 S3：探测后更新 sensor 状态字段，不动 node_status（status 由 recordNodeRunFinish 推进）。 */
  int updateSensorProbeState(
      WorkflowNodeRunUpdateSensorProbeStateParam parameters);
}
