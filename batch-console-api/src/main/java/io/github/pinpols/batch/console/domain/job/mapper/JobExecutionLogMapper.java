package io.github.pinpols.batch.console.domain.job.mapper;

import java.util.List;

import io.github.pinpols.batch.console.domain.job.entity.JobExecutionLogEntity;
import io.github.pinpols.batch.console.domain.job.query.JobExecutionLogQuery;

public interface JobExecutionLogMapper {

    List<JobExecutionLogEntity> selectByQuery(JobExecutionLogQuery query);

    long countByQuery(JobExecutionLogQuery query);
}
