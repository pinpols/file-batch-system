package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import com.example.batch.orchestrator.domain.param.MarkRunningParam;
import com.example.batch.orchestrator.domain.param.UpdateStepProgressParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobStepInstanceMapper {

  int insert(JobStepInstanceEntity entity);

  JobStepInstanceEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  JobStepInstanceEntity selectByJobTaskId(
      @Param("tenantId") String tenantId, @Param("jobTaskId") Long jobTaskId);

  List<JobStepInstanceEntity> selectByJobInstanceId(
      @Param("tenantId") String tenantId, @Param("jobInstanceId") Long jobInstanceId);

  int markRunning(MarkRunningParam param);

  int updateProgress(UpdateStepProgressParam param);

  int resetForRetryByJobTaskId(
      @Param("tenantId") String tenantId,
      @Param("jobTaskId") Long jobTaskId,
      @Param("retryCount") Integer retryCount,
      @Param("readyStatus") String readyStatus);
}
