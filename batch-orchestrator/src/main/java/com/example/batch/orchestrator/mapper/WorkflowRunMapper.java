package com.example.batch.orchestrator.mapper;

import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.orchestrator.domain.query.WorkflowRunQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface WorkflowRunMapper {

    int insert(WorkflowRunEntity entity);

    WorkflowRunEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    WorkflowRunEntity selectByRelatedJobInstanceId(@Param("tenantId") String tenantId,
                                                   @Param("relatedJobInstanceId") Long relatedJobInstanceId);

    List<WorkflowRunEntity> selectByQuery(WorkflowRunQuery query);

    int updateStatus(@Param("tenantId") String tenantId,
                     @Param("id") Long id,
                     @Param("runStatus") String runStatus,
                     @Param("currentNodeCode") String currentNodeCode,
                     @Param("finishedAt") Instant finishedAt);

    int markRunning(@Param("tenantId") String tenantId,
                    @Param("id") Long id,
                    @Param("runStatus") String runStatus,
                    @Param("currentNodeCode") String currentNodeCode,
                    @Param("startedAt") Instant startedAt);
}
