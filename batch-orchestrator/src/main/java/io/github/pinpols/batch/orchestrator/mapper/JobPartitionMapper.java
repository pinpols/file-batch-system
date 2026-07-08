package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.common.enums.PartitionStatus;
import io.github.pinpols.batch.orchestrator.domain.entity.JobPartitionEntity;
import io.github.pinpols.batch.orchestrator.domain.entity.QueuePartitionBacklogStats;
import io.github.pinpols.batch.orchestrator.domain.param.ClaimPartitionParam;
import io.github.pinpols.batch.orchestrator.domain.param.CountActiveByGroupParam;
import io.github.pinpols.batch.orchestrator.domain.param.MarkPartitionStatusParam;
import io.github.pinpols.batch.orchestrator.domain.param.QueueBacklogQueryParam;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchItem;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseBatchRow;
import io.github.pinpols.batch.orchestrator.domain.param.RenewLeaseParam;
import io.github.pinpols.batch.orchestrator.domain.query.JobPartitionQuery;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobPartitionMapper {

  List<JobPartitionEntity> selectByQuery(JobPartitionQuery query);

  // C-2: row-level lock to serialize concurrent partition counting during task outcome processing
  List<JobPartitionEntity> selectByQueryForUpdate(JobPartitionQuery query);

  int insert(JobPartitionEntity entity);

  /**
   * PERF(5.1): launch fan-out 多行 INSERT；PG getGeneratedKeys 按序回填每个元素的 {@code id}。 参数保持裸
   * List（MyBatis Jdbc3KeyGenerator 对 collection 参数回填 key 的标准形态）。
   */
  int insertBatch(List<JobPartitionEntity> entities);

  /**
   * PERF(5.3): 批量续租 — 单条 UPDATE ... FROM VALUES JOIN job_task ... RETURNING。 返回续租成功的 (tenantId,
   * taskId, cancelRequested) 行；缺席行=该项续租失败（语义同单条链路各前置/CAS 未命中）。
   */
  List<RenewLeaseBatchRow> renewLeaseBatch(
      @Param("items") List<RenewLeaseBatchItem> items,
      @Param("leaseExpireAt") Instant leaseExpireAt,
      @Param("runningStatus") String runningStatus);

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

  List<QueuePartitionBacklogStats> summarizeQueueBacklogByTenantAndQueueCodes(
      QueueBacklogQueryParam param);

  QueuePartitionBacklogStats summarizeGlobalQueueBacklog(
      @Param("createdStatus") String createdStatus,
      @Param("waitingStatus") String waitingStatus,
      @Param("readyStatus") String readyStatus,
      @Param("runningStatus") String runningStatus,
      @Param("retryingStatus") String retryingStatus);

  /**
   * 启动审计：统计 {@link PartitionStatus#READY}/{@link PartitionStatus#RUNNING} 且 lease 已过期、与 {@link
   * io.github.pinpols.batch.orchestrator.infrastructure.lease.PartitionLeaseReclaimScheduler}
   * 扫描口径一致的行数。 终态分区残留的 {@code lease_expire_at} 不计入。
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
