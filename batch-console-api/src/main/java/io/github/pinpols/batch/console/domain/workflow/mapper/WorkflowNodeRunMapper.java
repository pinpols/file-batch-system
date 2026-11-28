package io.github.pinpols.batch.console.domain.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeRunEntity;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeRunQuery;

public interface WorkflowNodeRunMapper {

    List<WorkflowNodeRunEntity> selectByQuery(WorkflowNodeRunQuery query);

    WorkflowNodeRunEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    long countByQuery(WorkflowNodeRunQuery query);
}
