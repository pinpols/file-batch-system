package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.query.JobInstanceQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobInstanceMapper {

    JobInstanceEntity selectById(@Param("tenantId") String tenantId,
                                 @Param("id") Long id);

    JobInstanceEntity selectByInstanceNo(@Param("tenantId") String tenantId,
                                         @Param("instanceNo") String instanceNo);

    JobInstanceEntity selectByTenantAndDedupKey(@Param("tenantId") String tenantId,
                                                @Param("dedupKey") String dedupKey);

    int insert(JobInstanceEntity entity);

    List<JobInstanceEntity> selectByQuery(JobInstanceQuery query);

    int updateProgress(@Param("tenantId") String tenantId,
                       @Param("id") Long id,
                       @Param("instanceStatus") String instanceStatus,
                       @Param("successPartitionCount") Integer successPartitionCount,
                       @Param("failedPartitionCount") Integer failedPartitionCount,
                       @Param("resultSummary") String resultSummary,
                       @Param("finishedAt") java.time.Instant finishedAt,
                       @Param("expectedVersion") Long expectedVersion);

    int markRunning(@Param("tenantId") String tenantId,
                    @Param("id") Long id,
                    @Param("instanceStatus") String instanceStatus,
                    @Param("expectedPartitionCount") Integer expectedPartitionCount,
                    @Param("startedAt") java.time.Instant startedAt,
                    @Param("expectedVersion") Long expectedVersion);

    int updateExpectedPartitionCount(@Param("tenantId") String tenantId,
                                     @Param("id") Long id,
                                     @Param("expectedPartitionCount") Integer expectedPartitionCount,
                                     @Param("expectedVersion") Long expectedVersion);

    List<JobInstanceEntity> selectSlaViolationCandidates(@Param("limit") int limit);

    long countSlaViolationCandidates();

    int markSlaAlerted(@Param("tenantId") String tenantId,
                       @Param("id") Long id,
                       @Param("slaAlertedAt") java.time.Instant slaAlertedAt);

    long countActiveByTenant(@Param("tenantId") String tenantId);

    long countActiveByTenantAndQueueCode(@Param("tenantId") String tenantId,
                                         @Param("queueCode") String queueCode);
}
