package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.WorkflowEdgeEntity;
import com.example.batch.console.domain.query.WorkflowEdgeQuery;
import java.util.List;

public interface WorkflowEdgeMapper {

    List<WorkflowEdgeEntity> selectByQuery(WorkflowEdgeQuery query);
}
