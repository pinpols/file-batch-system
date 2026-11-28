package io.github.pinpols.batch.orchestrator.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.orchestrator.domain.entity.WorkflowEdgeEntity;

public interface WorkflowEdgeMapper {

    List<WorkflowEdgeEntity> selectOutgoingEdges(
            @Param("workflowDefinitionId") Long workflowDefinitionId, @Param("fromNodeCode") String fromNodeCode);

    List<WorkflowEdgeEntity> selectIncomingEdges(
            @Param("workflowDefinitionId") Long workflowDefinitionId, @Param("toNodeCode") String toNodeCode);

    /** ADR-025 validator：一次拿全 workflow 所有 edge，避免逐节点查询。 */
    List<WorkflowEdgeEntity> selectAllByWorkflowDefinitionId(@Param("workflowDefinitionId") Long workflowDefinitionId);
}
