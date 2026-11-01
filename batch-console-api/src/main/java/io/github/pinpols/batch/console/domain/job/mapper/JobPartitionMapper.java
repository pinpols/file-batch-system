package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.console.domain.job.query.JobPartitionQuery;
import io.github.pinpols.batch.console.domain.job.view.JobPartitionView;
import java.util.List;

public interface JobPartitionMapper {

  List<JobPartitionView> selectByQuery(JobPartitionQuery query);

  long countByQuery(JobPartitionQuery query);
}
