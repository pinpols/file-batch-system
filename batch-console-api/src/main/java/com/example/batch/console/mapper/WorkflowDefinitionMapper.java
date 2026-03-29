package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowDefinitionEntity;
import com.example.batch.console.domain.query.WorkflowDefinitionQuery;
import java.util.List;

public interface WorkflowDefinitionMapper {

    List<WorkflowDefinitionEntity> selectByQuery(WorkflowDefinitionQuery query);

    long countByQuery(WorkflowDefinitionQuery query);

    WorkflowDefinitionEntity selectByUniqueKey(String tenantId, String workflowCode, Integer version);

    int upsertWorkflowDefinition(String tenantId,
                                 String workflowCode,
                                 String workflowName,
                                 String workflowType,
                                 Integer version,
                                 Boolean enabled,
                                 String description,
                                 String operatorId,
                                 String updatedBy);
}
