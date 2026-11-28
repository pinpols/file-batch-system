package io.github.pinpols.batch.console.domain.job.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Param;

import io.github.pinpols.batch.console.domain.job.query.JobStepInstanceQuery;
import io.github.pinpols.batch.console.domain.job.view.JobStepInstanceView;

public interface JobStepInstanceMapper {

    List<JobStepInstanceView> selectByQuery(JobStepInstanceQuery query);

    JobStepInstanceView selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    long countByQuery(JobStepInstanceQuery query);
}
