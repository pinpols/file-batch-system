package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.console.domain.job.entity.JobPartitionEntity;
import io.github.pinpols.batch.console.domain.job.query.JobPartitionQuery;
import java.util.List;

public interface JobPartitionMapper {

  List<JobPartitionEntity> selectByQuery(JobPartitionQuery query);

  long countByQuery(JobPartitionQuery query);
}
