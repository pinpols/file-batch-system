package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import io.github.pinpols.batch.orchestrator.domain.param.MarkRunningParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateStepProgressParam;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobStepInstanceMapper {

  int insert(JobStepInstanceEntity entity);

  /** PERF(5.1): createTasks 批量路径的 step 镜像多行 INSERT（fresh task，无幂等预检需求）。 */
  int insertBatch(List<JobStepInstanceEntity> entities);

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
