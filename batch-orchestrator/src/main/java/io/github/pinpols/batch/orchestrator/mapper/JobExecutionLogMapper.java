package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.orchestrator.domain.query.JobExecutionLogQuery;
import java.util.List;

public interface JobExecutionLogMapper {

  int insert(JobExecutionLogEntity entity);

  List<JobExecutionLogEntity> selectByQuery(JobExecutionLogQuery query);
}
