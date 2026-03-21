package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.WorkflowNodeRunEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowNodeRunMapper {

    int insert(WorkflowNodeRunEntity entity);

    WorkflowNodeRunEntity selectLatestByWorkflowRunIdAndNodeCode(@Param("workflowRunId") Long workflowRunId,
                                                                 @Param("nodeCode") String nodeCode);

    int updateStatus(@Param("id") Long id,
                     @Param("nodeStatus") String nodeStatus,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage,
                     @Param("durationMs") Long durationMs,
                     @Param("finishedAt") java.time.Instant finishedAt);

    List<WorkflowNodeRunEntity> selectByWorkflowRunId(@Param("workflowRunId") Long workflowRunId);
}
