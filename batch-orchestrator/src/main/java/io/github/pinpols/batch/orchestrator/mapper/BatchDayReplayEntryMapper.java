package io.github.pinpols.batch.orchestrator.mapper;

import io.github.pinpols.batch.orchestrator.domain.entity.BatchDayReplayEntryEntity;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayReplayEntryClaimIfCurrentParam;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayReplayEntryFailRejectedLaunchesParam;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayReplayEntryResetStaleUnboundParam;
import io.github.pinpols.batch.orchestrator.domain.param.BatchDayReplayEntryRetryParam;
import io.github.pinpols.batch.orchestrator.domain.param.UpdateBatchDayReplayEntryStatusParam;
import io.github.pinpols.batch.orchestrator.mapper.view.BatchDayReplayAssetPartitionImpactView;
import io.github.pinpols.batch.orchestrator.mapper.view.BatchDayReplayDispatchImpactView;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ADR-020 batch_day_replay_entry MyBatis 映射。 */
public interface BatchDayReplayEntryMapper {

  enum ReplayEntryStatus {
    PENDING,
    RUNNING
  }

  int insert(BatchDayReplayEntryEntity entity);

  /** 由已持久化的补跑根请求和最新节点尝试确定的当前执行记录。 */
  BatchDayReplayEntryEntity selectByCurrentExecution(
      @Param("tenantId") String tenantId,
      @Param("sessionId") Long sessionId,
      @Param("jobCode") String jobCode,
      @Param("jobInstanceId") Long jobInstanceId);

  BatchDayReplayEntryEntity selectByIdForUpdate(
      @Param("tenantId") String tenantId, @Param("sessionId") Long sessionId, @Param("id") Long id);

  /** 仅重试从未绑定过子执行的失败明细。 */
  default int retryFailedUnbound(String tenantId, Long sessionId, Long id, Instant now) {
    String targetStatus = ReplayEntryStatus.PENDING.name();
    BatchDayReplayEntryRetryParam param =
        new BatchDayReplayEntryRetryParam(tenantId, sessionId, id, now, targetStatus);
    return retryFailedUnboundWithStatus(param);
  }

  int retryFailedUnboundWithStatus(BatchDayReplayEntryRetryParam param);

  /** 批量插入；MyBatis foreach 拼 INSERT 多 VALUES。 */
  int insertBatch(@Param("records") List<BatchDayReplayEntryEntity> records);

  List<BatchDayReplayEntryEntity> selectBySessionId(@Param("sessionId") Long sessionId);

  /** 按租户和会话查询 entry；status 为空时返回全部状态。 */
  List<BatchDayReplayEntryEntity> selectBySessionAndStatus(
      @Param("sessionId") Long sessionId,
      @Param("tenantId") String tenantId,
      @Param("status") String status,
      @Param("limit") int limit);

  /** V202 前测试桩兼容入口；生产调用必须传 tenantId。 */
  default List<BatchDayReplayEntryEntity> selectBySessionAndStatus(
      Long sessionId, String status, int limit) {
    return selectBySessionAndStatus(sessionId, null, status, limit);
  }

  /** PENDING → RUNNING 原子抢占；返回 1 才允许创建实例。 */
  default int claimPending(Long id, String tenantId, Long sessionId, Instant now) {
    BatchDayReplayEntryClaimIfCurrentParam batchDayReplayEntryClaimIfCurrentParam =
        BatchDayReplayEntryClaimIfCurrentParam.builder()
            .id(id)
            .tenantId(tenantId)
            .sessionId(sessionId)
            .now(now)
            .expectedStatus(ReplayEntryStatus.PENDING.name())
            .targetStatus(ReplayEntryStatus.RUNNING.name())
            .build();
    return claimIfCurrent(batchDayReplayEntryClaimIfCurrentParam);
  }

  int claimIfCurrent(BatchDayReplayEntryClaimIfCurrentParam parameters);

  /** 回收启动前崩溃留下的 RUNNING entry；已绑定 rerun instance 的行绝不回收。 */
  default int resetStaleUnboundRunning(Long sessionId, Instant cutoff, Instant now) {
    BatchDayReplayEntryResetStaleUnboundParam batchDayReplayEntryResetStaleUnboundParam =
        BatchDayReplayEntryResetStaleUnboundParam.builder()
            .sessionId(sessionId)
            .cutoff(cutoff)
            .now(now)
            .expectedStatus(ReplayEntryStatus.RUNNING.name())
            .targetStatus(ReplayEntryStatus.PENDING.name())
            .build();
    return resetStaleUnbound(batchDayReplayEntryResetStaleUnboundParam);
  }

  int resetStaleUnbound(BatchDayReplayEntryResetStaleUnboundParam parameters);

  int bindCurrentExecutions(
      @Param("tenantId") String tenantId,
      @Param("sessionId") Long sessionId,
      @Param("now") Instant now);

  int failRejectedLaunches(BatchDayReplayEntryFailRejectedLaunchesParam parameters);

  /** 推进 status + 关联字段。 */
  int updateStatus(UpdateBatchDayReplayEntryStatusParam param);

  /** 用于 session 完成判断：每个状态各有多少 entry。 */
  long countBySessionAndStatus(@Param("sessionId") Long sessionId, @Param("status") String status);

  List<BatchDayReplayAssetPartitionImpactView> selectAssetPartitionImpacts(
      @Param("tenantId") String tenantId, @Param("businessKeys") Collection<String> businessKeys);

  List<BatchDayReplayDispatchImpactView> selectDispatchImpacts(
      @Param("tenantId") String tenantId,
      @Param("sourceInstanceIds") Collection<Long> sourceInstanceIds);
}
