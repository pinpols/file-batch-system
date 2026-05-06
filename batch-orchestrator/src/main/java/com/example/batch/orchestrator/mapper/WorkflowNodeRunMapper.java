package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.orchestrator.domain.param.UpdateNodeRunStatusParam;
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
}
