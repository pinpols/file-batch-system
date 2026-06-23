package io.github.pinpols.batch.console.domain.workflow.mapper;

import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.param.WorkflowNodeUpsertParam;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowNodeMapper {

  List<WorkflowNodeEntity> selectByQuery(WorkflowNodeQuery query);

  long countByQuery(WorkflowNodeQuery query);

  WorkflowNodeEntity selectByUniqueKey(Long workflowDefinitionId, String nodeCode);

  int deleteByWorkflowDefinitionId(@Param("workflowDefinitionId") Long workflowDefinitionId);

  int upsertWorkflowNode(WorkflowNodeUpsertParam param);
}
