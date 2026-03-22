package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowNodeEntity;
import com.example.batch.console.domain.query.WorkflowNodeQuery;
import java.util.List;

public interface WorkflowNodeMapper {

    List<WorkflowNodeEntity> selectByQuery(WorkflowNodeQuery query);
}
