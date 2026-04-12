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
}
