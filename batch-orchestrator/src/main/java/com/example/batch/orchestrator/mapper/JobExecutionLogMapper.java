package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import com.example.batch.orchestrator.domain.query.JobExecutionLogQuery;
import java.util.List;

public interface JobExecutionLogMapper {

  int insert(JobExecutionLogEntity entity);

  List<JobExecutionLogEntity> selectByQuery(JobExecutionLogQuery query);
}
