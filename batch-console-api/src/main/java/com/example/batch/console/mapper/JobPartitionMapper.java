package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobPartitionEntity;
import com.example.batch.console.domain.query.JobPartitionQuery;
import java.util.List;

public interface JobPartitionMapper {

  List<JobPartitionEntity> selectByQuery(JobPartitionQuery query);

  long countByQuery(JobPartitionQuery query);
}
