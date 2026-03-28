package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import java.util.List;

public interface WorkflowNodeMapper {

    List<WorkflowNodeEntity> selectByQuery(WorkflowNodeQuery query);

    WorkflowNodeEntity selectByUniqueKey(Long workflowDefinitionId, String nodeCode);

    int upsertWorkflowNode(Long workflowDefinitionId,
                           String nodeCode,
                           String nodeName,
                           String nodeType,
                           String relatedJobCode,
                           String relatedPipelineCode,
                           String workerGroup,
                           String windowCode,
                           Integer nodeOrder,
                           String retryPolicy,
                           Integer retryMaxCount,
                           Integer timeoutSeconds,
                           String nodeParams,
                           Boolean enabled);
}
