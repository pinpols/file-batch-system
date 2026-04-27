package com.example.batch.orchestrator.mapper;

import com.example.batch.orchestrator.domain.entity.JobInstanceEntity;
import com.example.batch.orchestrator.domain.query.BatchDayInstanceMetrics;
import com.example.batch.orchestrator.domain.query.JobInstanceQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface JobInstanceMapper {

  JobInstanceEntity selectById(@Param("tenantId") String tenantId, @Param("id") Long id);

  JobInstanceEntity selectByInstanceNo(
      @Param("tenantId") String tenantId, @Param("instanceNo") String instanceNo);

  JobInstanceEntity selectByTenantAndDedupKey(
      @Param("tenantId") String tenantId, @Param("dedupKey") String dedupKey);

  /** 同一 dedup_key 下已用到的最大 run_attempt，未出现过则返回 null。供 RERUN 路径原子推进。 */
  Integer selectMaxRunAttemptByDedupKey(
      @Param("tenantId") String tenantId, @Param("dedupKey") String dedupKey);

  int insert(JobInstanceEntity entity);

  List<JobInstanceEntity> selectByQuery(JobInstanceQuery query);

  int updateProgress(UpdateInstanceProgressParam param);

  int markRunning(MarkInstanceRunningParam param);

  int updateExpectedPartitionCount(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("expectedPartitionCount") Integer expectedPartitionCount,
      @Param("expectedVersion") Long expectedVersion);

  List<JobInstanceEntity> selectSlaViolationCandidates(@Param("limit") int limit);

  long countSlaViolationCandidates();

  int markSlaAlerted(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("slaAlertedAt") Instant slaAlertedAt);

  long countActiveByTenant(@Param("tenantId") String tenantId);

  long countActiveByTenantAndQueueCode(
      @Param("tenantId") String tenantId, @Param("queueCode") String queueCode);

  long countActiveByFairShareGroup(@Param("fairShareGroup") String fairShareGroup);

  /** 统计所有租户的运行中任务总量（WAITING/READY/RUNNING）。 */
  long countActiveAll();

  BatchDayInstanceMetrics selectBatchDayMetrics(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  List<JobInstanceEntity> selectBatchDayCatchUpCandidates(
      @Param("tenantId") String tenantId,
      @Param("calendarCode") String calendarCode,
      @Param("bizDate") LocalDate bizDate);

  int updateStatus(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("instanceStatus") String instanceStatus,
      @Param("finishedAt") Instant finishedAt,
      @Param("expectedVersion") Long expectedVersion);

  /**
   * 取同一 (tenantId, jobDefinitionId) 下最近一次成功(SUCCESS / PARTIAL_FAILED)实例。 用于增量模式启动新实例时把上一次的 {@code
   * high_water_mark_out} 当作本次 IN。没有历史成功实例(首次跑)返回 null, worker 在业务侧按"从头跑"处理。
   */
  JobInstanceEntity selectLastSuccessByJobDefinition(
      @Param("tenantId") String tenantId, @Param("jobDefinitionId") Long jobDefinitionId);

  /**
   * worker report 成功时把 OUT 水位回写。null 直接跳过(由调用方决策),非 null 才覆盖,避免把可能存在的旧水位清空。 不做版本检查:水位是单调推进的最终一致字段。
   */
  int updateHighWaterMarkOut(
      @Param("tenantId") String tenantId,
      @Param("id") Long id,
      @Param("highWaterMarkOut") String highWaterMarkOut);
}
