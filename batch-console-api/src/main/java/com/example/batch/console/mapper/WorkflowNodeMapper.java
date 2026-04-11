package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import com.example.batch.console.mapper.param.WorkflowNodeUpsertParam;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WorkflowNodeMapper {

    List<WorkflowNodeEntity> selectByQuery(WorkflowNodeQuery query);

    long countByQuery(WorkflowNodeQuery query);

    WorkflowNodeEntity selectByUniqueKey(Long workflowDefinitionId, String nodeCode);

    int deleteByWorkflowDefinitionId(@Param("workflowDefinitionId") Long workflowDefinitionId);

    int upsertWorkflowNode(WorkflowNodeUpsertParam param);
}
