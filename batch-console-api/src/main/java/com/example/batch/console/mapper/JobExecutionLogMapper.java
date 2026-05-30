package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobExecutionLogEntity;
import com.example.batch.console.domain.query.JobExecutionLogQuery;
import java.util.List;

public interface JobExecutionLogMapper {

  List<JobExecutionLogEntity> selectByQuery(JobExecutionLogQuery query);

  long countByQuery(JobExecutionLogQuery query);
}
