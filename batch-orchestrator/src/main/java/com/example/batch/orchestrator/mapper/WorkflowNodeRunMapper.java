package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowNodeRunMapper {

  int insert(WorkflowNodeRunEntity entity);

  WorkflowNodeRunEntity selectLatestByWorkflowRunIdAndNodeCode(
      @Param("workflowRunId") Long workflowRunId, @Param("nodeCode") String nodeCode);

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
      @Param("tenantId") String tenantId,
      @Param("workflowRunId") Long workflowRunId,
      @Param("nodeCode") String nodeCode);

  int updateStatus(UpdateNodeRunStatusParam param);

  List<WorkflowNodeRunEntity> selectByWorkflowRunId(@Param("workflowRunId") Long workflowRunId);

  /** ADR-018 Stage 5/7：扫 WAITING_DEPENDENCY 节点供 reconciler 重试 / 超时治理。 */
  List<WorkflowNodeRunEntity> selectByNodeStatus(
      @Param("nodeStatus") String nodeStatus, @Param("limit") int limit);

  /**
   * ADR-028 S3：扫到期 WAIT 节点（node_type=WAIT, status=RUNNING, next_probe_at &le; now）。
   *
   * <p>tenantId 为首条件，使 Citus 单分片路由；FOR UPDATE SKIP LOCKED 在单分片内合法。
   */
  List<WorkflowNodeRunEntity> selectDueWaitNodes(
      @Param("tenantId") String tenantId, @Param("now") Instant now, @Param("limit") int limit);

  /** ADR-028 S3：探测后更新 sensor 状态字段，不动 node_status（status 由 recordNodeRunFinish 推进）。 */
  int updateSensorProbeState(
      @Param("id") Long id,
      @Param("nextProbeAt") Instant nextProbeAt,
      @Param("lastProbeAt") Instant lastProbeAt,
      @Param("probeCount") int probeCount,
      @Param("errorCount") int errorCount);
}
