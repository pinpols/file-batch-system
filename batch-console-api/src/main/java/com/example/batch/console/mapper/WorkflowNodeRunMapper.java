package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowNodeRunEntity;
import com.example.batch.console.domain.query.WorkflowNodeRunQuery;
import java.util.List;

public interface WorkflowNodeRunMapper {

    List<WorkflowNodeRunEntity> selectByQuery(WorkflowNodeRunQuery query);
}
