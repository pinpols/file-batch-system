package com.example.batch.console.mapper;

import com.example.batch.common.persistence.entity.WorkflowRunEntity;
import com.example.batch.console.domain.query.WorkflowRunQuery;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WorkflowRunMapper {

    List<WorkflowRunEntity> selectByQuery(WorkflowRunQuery query);

    WorkflowRunEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    long countByQuery(WorkflowRunQuery query);
}
