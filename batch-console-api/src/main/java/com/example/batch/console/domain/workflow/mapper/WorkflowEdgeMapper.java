package com.example.batch.console.domain.workflow.mapper;

import com.example.batch.console.domain.workflow.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.workflow.param.WorkflowEdgeUpsertParam;
import com.example.batch.console.domain.workflow.query.WorkflowEdgeQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowEdgeMapper {

  List<WorkflowEdgeEntity> selectByQuery(WorkflowEdgeQuery query);

  long countByQuery(WorkflowEdgeQuery query);

  WorkflowEdgeEntity selectByUniqueKey(
      Long workflowDefinitionId, String fromNodeCode, String toNodeCode, String edgeType);

  int deleteByWorkflowDefinitionId(@Param("workflowDefinitionId") Long workflowDefinitionId);

  int upsertWorkflowEdge(WorkflowEdgeUpsertParam param);
}
