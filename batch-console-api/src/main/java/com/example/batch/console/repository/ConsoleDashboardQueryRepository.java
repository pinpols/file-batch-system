package com.example.batch.console.repository;

import com.example.batch.console.domain.ConsoleJdbcQueryAnchor;
import com.example.batch.console.domain.view.dashboard.ActivePartitionView;
import com.example.batch.console.domain.view.dashboard.ConfigDependentView;
import com.example.batch.console.domain.view.dashboard.DayCountView;
import com.example.batch.console.domain.view.dashboard.DaySeverityCountView;
import com.example.batch.console.domain.view.dashboard.DayStatusCountView;
import com.example.batch.console.domain.view.dashboard.ExecutionProgressView;
import com.example.batch.console.domain.view.dashboard.SeverityCountView;
import com.example.batch.console.domain.view.dashboard.SlaDayView;
import com.example.batch.console.domain.view.dashboard.SlaJobReportView;
import com.example.batch.console.domain.view.dashboard.SlaStatsView;
import com.example.batch.console.domain.view.dashboard.StatusCountView;
import com.example.batch.console.domain.view.dashboard.TypeCountView;
import com.example.batch.console.domain.view.dashboard.WorkerGroupStatusCountView;
import java.util.List;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface ConsoleDashboardQueryRepository extends Repository<ConsoleJdbcQueryAnchor, Long> {

  @Query(
      """
      SELECT instance_status AS status, count(1) AS count
        FROM batch.job_instance
       WHERE tenant_id = :tenantId
       GROUP BY instance_status
      """)
  List<StatusCountView> jobStatusCounts(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT cast(created_at as date) AS day, instance_status AS status, count(1) AS count
        FROM batch.job_instance
       WHERE tenant_id = :tenantId
         AND created_at >= current_date - :days
       GROUP BY day, instance_status
       ORDER BY day
      """)
  List<DayStatusCountView> jobDailyTrend(
      @Param("tenantId") String tenantId, @Param("days") int days);

  @Query(
      """
      SELECT trigger_type AS type, count(1) AS count
        FROM batch.trigger_request
       WHERE tenant_id = :tenantId
       GROUP BY trigger_type
      """)
  List<TypeCountView> triggerTypeCounts(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT cast(created_at as date) AS day, count(1) AS count
        FROM batch.trigger_request
       WHERE tenant_id = :tenantId
         AND created_at >= current_date - :days
       GROUP BY day
       ORDER BY day
      """)
  List<DayCountView> triggerDailyTrend(@Param("tenantId") String tenantId, @Param("days") int days);

  @Query(
      """
      SELECT status, count(1) AS count
        FROM batch.worker_registry
       WHERE tenant_id = :tenantId
       GROUP BY status
      """)
  List<StatusCountView> workerStatusCounts(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT worker_group AS workerGroup, status, count(1) AS count
        FROM batch.worker_registry
       WHERE tenant_id = :tenantId
       GROUP BY worker_group, status
       ORDER BY worker_group
      """)
  List<WorkerGroupStatusCountView> workerGroupStatusCounts(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT p.worker_code AS workerCode, count(1) AS activePartitions
        FROM batch.job_partition p
       WHERE p.tenant_id = :tenantId
         AND p.partition_status IN ('READY', 'RUNNING')
         AND p.worker_code IS NOT NULL
       GROUP BY p.worker_code
       ORDER BY activePartitions DESC
      """)
  List<ActivePartitionView> activePartitionsByWorker(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT severity, count(1) AS count
        FROM batch.alert_event
       WHERE tenant_id = :tenantId
       GROUP BY severity
      """)
  List<SeverityCountView> alertSeverityCounts(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT cast(created_at as date) AS day, severity, count(1) AS count
        FROM batch.alert_event
       WHERE tenant_id = :tenantId
         AND created_at >= current_date - :days
       GROUP BY day, severity
       ORDER BY day
      """)
  List<DaySeverityCountView> alertDailyTrend(
      @Param("tenantId") String tenantId, @Param("days") int days);

  @Query(
      """
      SELECT count(1) FILTER (WHERE deadline_at IS NOT NULL AND finished_at > deadline_at) AS breached,
             count(1) FILTER (WHERE deadline_at IS NOT NULL AND (finished_at IS NULL OR finished_at <= deadline_at)) AS onTime,
             count(1) FILTER (WHERE deadline_at IS NOT NULL) AS totalWithSla,
             round(avg(EXTRACT(EPOCH FROM (finished_at - started_at)))::numeric, 2) AS avgDurationSeconds
        FROM batch.job_instance
       WHERE tenant_id = :tenantId
         AND created_at >= current_date - :days
         AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED')
      """)
  SlaStatsView slaStats(@Param("tenantId") String tenantId, @Param("days") int days);

  @Query(
      """
      SELECT cast(created_at as date) AS day,
             count(1) FILTER (WHERE deadline_at IS NOT NULL AND finished_at > deadline_at) AS breached,
             count(1) FILTER (WHERE deadline_at IS NOT NULL AND finished_at <= deadline_at) AS onTime
        FROM batch.job_instance
       WHERE tenant_id = :tenantId
         AND created_at >= current_date - :days
         AND instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED')
       GROUP BY day
       ORDER BY day
      """)
  List<SlaDayView> slaDailyTrend(@Param("tenantId") String tenantId, @Param("days") int days);

  @Query(
      """
      SELECT id, job_code AS code, job_name AS name
        FROM batch.job_definition
       WHERE tenant_id = :tenantId AND queue_code = :configCode
      """)
  List<ConfigDependentView> jobsByQueueCode(
      @Param("tenantId") String tenantId, @Param("configCode") String configCode);

  @Query(
      """
      SELECT id, job_code AS code, job_name AS name
        FROM batch.job_definition
       WHERE tenant_id = :tenantId AND calendar_code = :configCode
      """)
  List<ConfigDependentView> jobsByCalendarCode(
      @Param("tenantId") String tenantId, @Param("configCode") String configCode);

  @Query(
      """
      SELECT id, job_code AS code, job_name AS name
        FROM batch.job_definition
       WHERE tenant_id = :tenantId AND window_code = :configCode
      """)
  List<ConfigDependentView> jobsByWindowCode(
      @Param("tenantId") String tenantId, @Param("configCode") String configCode);

  @Query(
      """
      SELECT id, job_code AS code, job_name AS name
        FROM batch.job_definition
       WHERE tenant_id = :tenantId AND worker_group = :configCode
      """)
  List<ConfigDependentView> jobsByWorkerGroup(
      @Param("tenantId") String tenantId, @Param("configCode") String configCode);

  @Query(
      """
      SELECT i.id,
             i.job_code AS jobCode,
             i.instance_no AS instanceNo,
             i.instance_status AS instanceStatus,
             i.expected_partition_count AS expectedPartitions,
             i.success_partition_count AS successPartitions,
             i.failed_partition_count AS failedPartitions,
             i.started_at AS startedAt,
             i.finished_at AS finishedAt
        FROM batch.job_instance i
       WHERE i.tenant_id = :tenantId
         AND i.job_code = :jobCode
         AND i.biz_date = CAST(:bizDate AS DATE)
       ORDER BY i.id DESC
      """)
  List<ExecutionProgressView> executionProgress(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("bizDate") String bizDate);

  @Query(
      """
      SELECT count(1) AS count FROM batch.job_definition WHERE tenant_id = :tenantId
      """)
  Long countJobDefinitions(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT count(1) AS count FROM batch.job_instance
       WHERE tenant_id = :tenantId AND created_at >= current_date - :days
      """)
  Long countRecentJobInstances(@Param("tenantId") String tenantId, @Param("days") int days);

  @Query(
      """
      SELECT count(1) AS count FROM batch.workflow_definition WHERE tenant_id = :tenantId
      """)
  Long countWorkflowDefinitions(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT count(1) AS count FROM batch.file_record
       WHERE tenant_id = :tenantId AND created_at >= current_date - :days
      """)
  Long countRecentFiles(@Param("tenantId") String tenantId, @Param("days") int days);

  @Query(
      """
      SELECT count(1) AS count FROM batch.file_channel_config WHERE tenant_id = :tenantId
      """)
  Long countFileChannels(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT count(1) AS count FROM batch.file_template_config WHERE tenant_id = :tenantId
      """)
  Long countFileTemplates(@Param("tenantId") String tenantId);

  @Query(
      """
      SELECT i.job_code AS jobCode,
             d.job_name AS jobName,
             count(1) AS totalInstances,
             count(1) FILTER (WHERE i.instance_status = 'SUCCESS') AS successCount,
             count(1) FILTER (WHERE i.instance_status IN ('FAILED','PARTIAL_FAILED')) AS failedCount,
             count(1) FILTER (WHERE i.deadline_at IS NOT NULL AND i.finished_at > i.deadline_at) AS slaBreached,
             count(1) FILTER (WHERE i.deadline_at IS NOT NULL AND (i.finished_at IS NULL OR i.finished_at <= i.deadline_at)) AS slaOnTime,
             round(avg(EXTRACT(EPOCH FROM (i.finished_at - i.started_at)))::numeric, 2) AS avgDurationSeconds,
             round(max(EXTRACT(EPOCH FROM (i.finished_at - i.started_at)))::numeric, 2) AS maxDurationSeconds,
             coalesce(sum(i.expected_partition_count), 0) AS totalPartitions
        FROM batch.job_instance i
        LEFT JOIN batch.job_definition d ON d.tenant_id = i.tenant_id AND d.job_code = i.job_code
       WHERE i.tenant_id = :tenantId
         AND i.created_at >= current_date - :days
         AND i.instance_status IN ('SUCCESS', 'FAILED', 'PARTIAL_FAILED')
       GROUP BY i.job_code, d.job_name
       ORDER BY slaBreached DESC, totalInstances DESC
      """)
  List<SlaJobReportView> slaJobReport(@Param("tenantId") String tenantId, @Param("days") int days);
}
