package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobStepInstanceEntity;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobStepInstanceMapper {

    int insert(JobStepInstanceEntity entity);

    JobStepInstanceEntity selectById(@Param("tenantId") String tenantId,
                                     @Param("id") Long id);

    JobStepInstanceEntity selectByJobTaskId(@Param("tenantId") String tenantId,
                                            @Param("jobTaskId") Long jobTaskId);

    List<JobStepInstanceEntity> selectByJobInstanceId(@Param("tenantId") String tenantId,
                                                      @Param("jobInstanceId") Long jobInstanceId);

    int markRunning(@Param("tenantId") String tenantId,
                    @Param("id") Long id,
                    @Param("startedAt") Instant startedAt,
                    @Param("expectedVersion") Long expectedVersion);

    int updateProgress(@Param("tenantId") String tenantId,
                       @Param("id") Long id,
                       @Param("stepStatus") String stepStatus,
                       @Param("retryCount") Integer retryCount,
                       @Param("relatedFileId") Long relatedFileId,
                       @Param("resultSummary") String resultSummary,
                       @Param("errorCode") String errorCode,
                       @Param("errorMessage") String errorMessage,
                       @Param("finishedAt") Instant finishedAt,
                       @Param("expectedVersion") Long expectedVersion);

    int resetForRetryByJobTaskId(@Param("tenantId") String tenantId,
                                 @Param("jobTaskId") Long jobTaskId,
                                 @Param("retryCount") Integer retryCount);
}
