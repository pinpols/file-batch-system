package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;

import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

public interface JobPartitionMapper {

    List<JobPartitionEntity> selectByQuery(JobPartitionQuery query);

    // C-2: row-level lock to serialize concurrent partition counting during task outcome processing
    List<JobPartitionEntity> selectByQueryForUpdate(JobPartitionQuery query);

    int insert(JobPartitionEntity entity);

    JobPartitionEntity selectByTenantAndJobInstanceIdAndPartitionNo(
            @Param("tenantId") String tenantId,
            @Param("jobInstanceId") Long jobInstanceId,
            @Param("partitionNo") Integer partitionNo);

    JobPartitionEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    int claimPartition(ClaimPartitionParam param);

    int renewLease(
            @Param("tenantId") String tenantId,
            @Param("id") Long id,
            @Param("workerCode") String workerCode,
            @Param("leaseExpireAt") Instant leaseExpireAt);

    int markRetrying(
            @Param("tenantId") String tenantId,
            @Param("id") Long id,
            @Param("retryCount") Integer retryCount,
            @Param("retryingStatus") String retryingStatus,
            @Param("expectedVersion") Long expectedVersion);

    int updateOutputSummary(
            @Param("tenantId") String tenantId,
            @Param("id") Long id,
            @Param("outputSummary") String outputSummary);

    int updateInputSnapshot(
            @Param("tenantId") String tenantId,
            @Param("id") Long id,
            @Param("inputSnapshot") String inputSnapshot);

    int markStatus(MarkPartitionStatusParam param);

    List<JobPartitionEntity> selectExpiredLeases(
            @Param("tenantId") String tenantId,
            @Param("readyStatus") String readyStatus,
            @Param("runningStatus") String runningStatus);

    List<JobPartitionEntity> selectExpiredLeasesGlobal(
            @Param("readyStatus") String readyStatus, @Param("runningStatus") String runningStatus);

    List<JobPartitionEntity> selectWaitingPartitionsGlobal(
            @Param("batchSize") int batchSize, @Param("waitingStatus") String waitingStatus);

    int resetForDispatch(
            @Param("tenantId") String tenantId,
            @Param("id") Long id,
            @Param("readyStatus") String readyStatus,
            @Param("expectedVersion") Long expectedVersion);

    int promoteStatus(
            @Param("tenantId") String tenantId,
            @Param("id") Long id,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("expectedVersion") Long expectedVersion);

    long countActiveByTenant(
            @Param("tenantId") String tenantId,
            @Param("waitingStatus") String waitingStatus,
            @Param("readyStatus") String readyStatus,
            @Param("runningStatus") String runningStatus,
            @Param("retryingStatus") String retryingStatus);

    long countActiveByTenantAndWorkerGroup(CountActiveByGroupParam param);
}
