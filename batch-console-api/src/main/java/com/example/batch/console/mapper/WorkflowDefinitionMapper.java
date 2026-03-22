package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import java.util.List;

public interface WorkflowDefinitionMapper {

    List<WorkflowDefinitionEntity> selectByQuery(WorkflowDefinitionQuery query);
}
