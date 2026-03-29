package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import java.util.List;

public interface WorkflowEdgeMapper {

    List<WorkflowEdgeEntity> selectByQuery(WorkflowEdgeQuery query);

    long countByQuery(WorkflowEdgeQuery query);

    WorkflowEdgeEntity selectByUniqueKey(Long workflowDefinitionId, String fromNodeCode, String toNodeCode, String edgeType);

    int upsertWorkflowEdge(Long workflowDefinitionId,
                           String fromNodeCode,
                           String toNodeCode,
                           String edgeType,
                           String conditionExpr,
                           Boolean enabled);
}
