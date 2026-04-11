package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.console.domain.query.WorkflowNodeRunQuery;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface WorkflowNodeRunMapper {

    List<WorkflowNodeRunEntity> selectByQuery(WorkflowNodeRunQuery query);

    WorkflowNodeRunEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    long countByQuery(WorkflowNodeRunQuery query);
}
