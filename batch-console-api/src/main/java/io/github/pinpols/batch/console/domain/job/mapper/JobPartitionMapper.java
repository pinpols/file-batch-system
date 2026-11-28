package io.github.pinpols.batch.console.domain.job.mapper;

import java.util.List;

import io.github.pinpols.batch.console.domain.job.query.JobPartitionQuery;
import io.github.pinpols.batch.console.domain.job.view.JobPartitionView;

public interface JobPartitionMapper {

    List<JobPartitionView> selectByQuery(JobPartitionQuery query);

    long countByQuery(JobPartitionQuery query);
}
