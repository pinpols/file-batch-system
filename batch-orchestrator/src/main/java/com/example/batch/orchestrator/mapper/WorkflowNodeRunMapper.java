package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowNodeRunMapper {

    int insert(WorkflowNodeRunEntity entity);

    WorkflowNodeRunEntity selectLatestByWorkflowRunIdAndNodeCode(@Param("workflowRunId") Long workflowRunId,
                                                                 @Param("nodeCode") String nodeCode);

    // C-1/C-3: pessimistic lock to serialize concurrent recordNodeRunFinish / isNodeAlreadyActivated
    WorkflowNodeRunEntity selectLatestForUpdate(@Param("workflowRunId") Long workflowRunId,
                                                @Param("nodeCode") String nodeCode);

    int updateStatus(UpdateNodeRunStatusParam param);

    List<WorkflowNodeRunEntity> selectByWorkflowRunId(@Param("workflowRunId") Long workflowRunId);
}
