package com.example.batch.orchestrator.mapper;

import com.example.batch.common.enums.PartitionStatus;
import com.example.batch.orchestrator.domain.entity.JobPartitionEntity;
import com.example.batch.orchestrator.domain.param.ClaimPartitionParam;
import com.example.batch.orchestrator.domain.param.CountActiveByGroupParam;
import com.example.batch.orchestrator.domain.param.MarkPartitionStatusParam;
import com.example.batch.orchestrator.domain.param.RenewLeaseParam;
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

  int renewLease(RenewLeaseParam param);

  int markRetrying(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("retryCount") Integer retryCount,
      @Param("retryingStatus") String retryingStatus,
      @Param("expectedVersion") Long expectedVersion);

  int updateOutputSummary(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("outputSummary") String outputSummary,
      @Param("expectedInvocationId") String expectedInvocationId);

  int updateInputSnapshot(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("inputSnapshot") String inputSnapshot,
      @Param("expectedInvocationId") String expectedInvocationId);

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
   * 回退扫描：partition_status=READY 且 lease_expire_at IS NULL 但仍有 RUNNING task 的死态。 仅用于升级前历史残留清理；新代码已通过
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
   * 启动审计：统计 {@link PartitionStatus#READY}/{@link PartitionStatus#RUNNING} 且 lease 已过期、与 {@link
   * com.example.batch.orchestrator.infrastructure.lease.PartitionLeaseReclaimScheduler} 扫描口径一致的行数。
   * 终态分区残留的 {@code lease_expire_at} 不计入。
   */
  long countLeaseExpired(
      @Param("readyStatus") String readyStatus, @Param("runningStatus") String runningStatus);

  /**
   * 当 {@code job_instance} 已进入终态但子分区仍为非终态（历史并发/缺陷残留）时，批量收敛分区状态并清空 lease， 避免 {@code
   * DefaultPartitionThrottle} 把 READY/RUNNING/RETRYING 计入活跃配额导致泄漏。
   */
  int closeNonTerminalPartitionsForTerminalInstance(
      @Param("tenantId") String tenantId,
      @Param("jobInstanceId") Long jobInstanceId,
      @Param("targetPartitionStatus") String targetPartitionStatus);
}
