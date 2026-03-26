package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobPartitionMapper {

    List<JobPartitionEntity> selectByQuery(JobPartitionQuery query);

    int insert(JobPartitionEntity entity);

    JobPartitionEntity selectByTenantAndJobInstanceIdAndPartitionNo(@Param("tenantId") String tenantId,
                                                                    @Param("jobInstanceId") Long jobInstanceId,
                                                                    @Param("partitionNo") Integer partitionNo);

    JobPartitionEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

    int claimPartition(@Param("tenantId") String tenantId,
                       @Param("id") Long id,
                       @Param("workerCode") String workerCode,
                       @Param("leaseExpireAt") java.time.Instant leaseExpireAt,
                       @Param("fromStatus") String fromStatus,
                       @Param("toStatus") String toStatus,
                       @Param("expectedVersion") Long expectedVersion);

    int renewLease(@Param("tenantId") String tenantId,
                   @Param("id") Long id,
                   @Param("workerCode") String workerCode,
                   @Param("leaseExpireAt") java.time.Instant leaseExpireAt);

    int markRetrying(@Param("tenantId") String tenantId,
                     @Param("id") Long id,
                     @Param("retryCount") Integer retryCount,
                     @Param("retryingStatus") String retryingStatus,
                     @Param("expectedVersion") Long expectedVersion);

    int updateOutputSummary(@Param("tenantId") String tenantId,
                            @Param("id") Long id,
                            @Param("outputSummary") String outputSummary);

    int updateInputSnapshot(@Param("tenantId") String tenantId,
                            @Param("id") Long id,
                            @Param("inputSnapshot") String inputSnapshot);

    int markStatus(@Param("tenantId") String tenantId,
                   @Param("id") Long id,
                   @Param("partitionStatus") String partitionStatus,
                   @Param("runningStatus") String runningStatus,
                   @Param("terminalStatus1") String terminalStatus1,
                   @Param("terminalStatus2") String terminalStatus2,
                   @Param("terminalStatus3") String terminalStatus3,
                   @Param("terminalStatus4") String terminalStatus4,
                   @Param("expectedVersion") Long expectedVersion);

    List<JobPartitionEntity> selectExpiredLeases(@Param("tenantId") String tenantId,
                                                 @Param("readyStatus") String readyStatus,
                                                 @Param("runningStatus") String runningStatus);

    List<JobPartitionEntity> selectExpiredLeasesGlobal(@Param("readyStatus") String readyStatus,
                                                        @Param("runningStatus") String runningStatus);

    List<JobPartitionEntity> selectWaitingPartitionsGlobal(@Param("batchSize") int batchSize,
                                                            @Param("waitingStatus") String waitingStatus);

    int resetForDispatch(@Param("tenantId") String tenantId,
                         @Param("id") Long id,
                         @Param("readyStatus") String readyStatus,
                         @Param("expectedVersion") Long expectedVersion);

    int promoteStatus(@Param("tenantId") String tenantId,
                      @Param("id") Long id,
                      @Param("fromStatus") String fromStatus,
                      @Param("toStatus") String toStatus,
                      @Param("expectedVersion") Long expectedVersion);

    long countActiveByTenant(@Param("tenantId") String tenantId,
                              @Param("waitingStatus") String waitingStatus,
                              @Param("readyStatus") String readyStatus,
                              @Param("runningStatus") String runningStatus,
                              @Param("retryingStatus") String retryingStatus);

    long countActiveByTenantAndWorkerGroup(@Param("tenantId") String tenantId,
                                           @Param("workerGroup") String workerGroup,
                                           @Param("waitingStatus") String waitingStatus,
                                           @Param("readyStatus") String readyStatus,
                                           @Param("runningStatus") String runningStatus,
                                           @Param("retryingStatus") String retryingStatus);
}
