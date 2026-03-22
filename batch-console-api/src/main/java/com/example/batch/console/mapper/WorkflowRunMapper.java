package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowRunEntity;
import com.example.batch.console.domain.query.WorkflowRunQuery;
import java.util.List;

public interface WorkflowRunMapper {

    List<WorkflowRunEntity> selectByQuery(WorkflowRunQuery query);
}
