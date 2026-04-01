package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import com.example.batch.console.mapper.param.WorkflowEdgeUpsertParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowEdgeMapper {

    List<WorkflowEdgeEntity> selectByQuery(WorkflowEdgeQuery query);

    long countByQuery(WorkflowEdgeQuery query);

    WorkflowEdgeEntity selectByUniqueKey(Long workflowDefinitionId, String fromNodeCode, String toNodeCode, String edgeType);

    int deleteByWorkflowDefinitionId(@Param("workflowDefinitionId") Long workflowDefinitionId);

    int upsertWorkflowEdge(WorkflowEdgeUpsertParam param);
}
