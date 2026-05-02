package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.param.ClaimPartitionParam;
import com.example.batch.orchestrator.domain.param.CountActiveByGroupParam;
import com.example.batch.orchestrator.domain.param.MarkPartitionStatusParam;
import com.example.batch.orchestrator.domain.query.JobPartitionQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

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
      @Param("readyStatus") String readyStatus,
      @Param("runningStatus") String runningStatus,
      @Param("batchSize") Integer batchSize);

  /**
   * 兜底扫描：partition_status=READY 且 lease_expire_at IS NULL 但仍有 RUNNING task 的死态。 仅用于升级前历史残留清理；新代码已通过
   * REQUIRES_NEW + 抛异常回滚消除产生路径。
   */
  List<JobPartitionEntity> selectOrphanReadyPartitionsWithRunningTask(
      @Param("readyStatus") String readyStatus,
      @Param("runningTaskStatus") String runningTaskStatus,
      @Param("olderThan") Instant olderThan,
      @Param("batchSize") int batchSize);

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

  /**
   * 启动审计：统计 lease_expire_at 已过期、仍未被 PartitionLeaseReclaimScheduler 回收的 partition 数。 非 0
   * 说明上轮调度漏跑或异常。
   */
  long countLeaseExpired();
}
