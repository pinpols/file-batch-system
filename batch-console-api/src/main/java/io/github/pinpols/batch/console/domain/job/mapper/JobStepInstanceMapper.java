package io.github.pinpols.batch.console.domain.job.mapper;

import io.github.pinpols.batch.console.domain.job.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.console.domain.job.query.JobStepInstanceQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobStepInstanceMapper {

  List<JobStepInstanceEntity> selectByQuery(JobStepInstanceQuery query);

  JobStepInstanceEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  long countByQuery(JobStepInstanceQuery query);
}
