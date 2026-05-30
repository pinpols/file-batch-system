package com.example.batch.console.domain.job.mapper;

import com.example.batch.console.domain.job.entity.JobExecutionLogEntity;
import com.example.batch.console.domain.job.query.JobExecutionLogQuery;
import java.util.List;

public interface JobExecutionLogMapper {

  List<JobExecutionLogEntity> selectByQuery(JobExecutionLogQuery query);

  long countByQuery(JobExecutionLogQuery query);
}
