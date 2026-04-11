package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkflowEdgeEntity;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WorkflowEdgeMapper {

    List<WorkflowEdgeEntity> selectOutgoingEdges(
            @Param("workflowDefinitionId") Long workflowDefinitionId,
            @Param("fromNodeCode") String fromNodeCode);

    List<WorkflowEdgeEntity> selectIncomingEdges(
            @Param("workflowDefinitionId") Long workflowDefinitionId,
            @Param("toNodeCode") String toNodeCode);
}
