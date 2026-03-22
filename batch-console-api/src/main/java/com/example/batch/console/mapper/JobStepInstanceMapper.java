package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobStepInstanceEntity;
import com.example.batch.console.domain.query.JobStepInstanceQuery;
import java.util.List;

public interface JobStepInstanceMapper {

    List<JobStepInstanceEntity> selectByQuery(JobStepInstanceQuery query);
}
