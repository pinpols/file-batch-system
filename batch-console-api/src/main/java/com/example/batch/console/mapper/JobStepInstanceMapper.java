package com.example.batch.console.mapper;

import com.example.batch.console.domain.entity.JobStepInstanceEntity;
import com.example.batch.console.domain.query.JobStepInstanceQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobStepInstanceMapper {

  List<JobStepInstanceEntity> selectByQuery(JobStepInstanceQuery query);

  JobStepInstanceEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  long countByQuery(JobStepInstanceQuery query);
}
