package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowNodeMapper {

  WorkflowNodeEntity selectByWorkflowDefinitionIdAndNodeCode(
      @Param("workflowDefinitionId") Long workflowDefinitionId, @Param("nodeCode") String nodeCode);

  List<WorkflowNodeEntity> selectByWorkflowDefinitionId(
      @Param("workflowDefinitionId") Long workflowDefinitionId);

  /**
   * S3 / R3-P1-1：批量查询多个 nodeCode 对应的节点定义，消除 resolveNextNodes 在 outgoing edges 循环里的 N+1。
   * 空集合调用方需自行避免（mapper xml 的 foreach 空集合会产生非法 SQL）。
   */
  List<WorkflowNodeEntity> selectByWorkflowDefinitionIdAndNodeCodesIn(
      @Param("workflowDefinitionId") Long workflowDefinitionId,
      @Param("nodeCodes") Collection<String> nodeCodes);
}
