package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobInstanceEntity;
import com.example.batch.console.domain.query.JobInstanceQuery;
import java.util.List;

public interface JobInstanceMapper {

    List<JobInstanceEntity> selectByQuery(JobInstanceQuery query);
}
