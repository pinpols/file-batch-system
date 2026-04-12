package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkflowNodeEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowNodeMapper {

  WorkflowNodeEntity selectByWorkflowDefinitionIdAndNodeCode(
      @Param("workflowDefinitionId") Long workflowDefinitionId, @Param("nodeCode") String nodeCode);

  List<WorkflowNodeEntity> selectByWorkflowDefinitionId(
      @Param("workflowDefinitionId") Long workflowDefinitionId);
}
