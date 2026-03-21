package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobDefinitionEntity;
import com.example.batch.console.domain.query.JobDefinitionQuery;
import java.util.List;

public interface JobDefinitionMapper {

    List<JobDefinitionEntity> selectByQuery(JobDefinitionQuery query);
}
