package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowNodeRunMapper {

  int insert(WorkflowNodeRunEntity entity);

  WorkflowNodeRunEntity selectLatestByWorkflowRunIdAndNodeCode(
      @Param("workflowRunId") Long workflowRunId, @Param("nodeCode") String nodeCode);

  // C-1/C-3: pessimistic lock to serialize concurrent recordNodeRunFinish /
  // isNodeAlreadyActivated
  WorkflowNodeRunEntity selectLatestForUpdate(
      @Param("workflowRunId") Long workflowRunId, @Param("nodeCode") String nodeCode);

  int updateStatus(UpdateNodeRunStatusParam param);

  List<WorkflowNodeRunEntity> selectByWorkflowRunId(@Param("workflowRunId") Long workflowRunId);

  /** ADR-018 Stage 5/7：扫 WAITING_DEPENDENCY 节点供 reconciler 重试 / 超时治理。 */
  List<WorkflowNodeRunEntity> selectByNodeStatus(
      @Param("nodeStatus") String nodeStatus, @Param("limit") int limit);

  /** ADR-028 S3：扫到期 WAIT 节点（node_type=WAIT, status=RUNNING, next_probe_at &le; now）。 */
  List<WorkflowNodeRunEntity> selectDueWaitNodes(
      @Param("now") Instant now, @Param("limit") int limit);

  /** ADR-028 S3：探测后更新 sensor 状态字段，不动 node_status（status 由 recordNodeRunFinish 推进）。 */
  int updateSensorProbeState(
      @Param("id") Long id,
      @Param("nextProbeAt") Instant nextProbeAt,
      @Param("lastProbeAt") Instant lastProbeAt,
      @Param("probeCount") int probeCount,
      @Param("errorCount") int errorCount);
}
