package io.github.pinpols.batch.console.domain.workflow.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.common.persistence.entity.WorkflowRunEntity;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowRunQuery;

public interface WorkflowRunMapper {

    List<WorkflowRunEntity> selectByQuery(WorkflowRunQuery query);

    WorkflowRunEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    long countByQuery(WorkflowRunQuery query);

    long countByStatuses(@Param("tenantId") String tenantId, @Param("statuses") List<String> statuses);
}
