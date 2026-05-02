package com.example.batch.console.mapper;

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
import org.apache.ibatis.annotations.Param;

/**
 * console dashboard 聚合 / 趋势 / SLA 查询 (MyBatis,迁移自 ConsoleDashboardQueryRepository)。
 *
 * <p>23 个查询分 4 类:
 *
 * <ol>
 *   <li><b>简单聚合</b>(7):jobStatusCounts / triggerTypeCounts / workerStatusCounts /
 *       workerGroupStatusCounts / activePartitionsByWorker / alertSeverityCounts /
 *       executionProgress
 *   <li><b>日趋势</b>(4):jobDailyTrend / triggerDailyTrend / alertDailyTrend / slaDailyTrend
 *   <li><b>配置依赖反查</b>(4):jobsByQueueCode / jobsByCalendarCode / jobsByWindowCode /
 *       jobsByWorkerGroup
 *   <li><b>计数 / SLA</b>(8):countJobDefinitions / countRecentJobInstances / countWorkflowDefinitions
 *       / countRecentFiles / countFileChannels / countFileTemplates / slaStats / slaJobReport
 * </ol>
 *
 * <p>所有 SQL 走读副本(service 层 {@code @Transactional(readOnly = true)});PostgreSQL 特化语法 ({@code FILTER
 * (WHERE ...)} / {@code EXTRACT(EPOCH FROM ...)} / {@code ::numeric}) 集中在 SLA 块。
 */
public interface ConsoleDashboardQueryMapper {

  // ── 简单聚合 ────────────────────────────────────────────────────────────────

  List<StatusCountView> jobStatusCounts(@Param("tenantId") String tenantId);

  List<TypeCountView> triggerTypeCounts(@Param("tenantId") String tenantId);

  List<StatusCountView> workerStatusCounts(@Param("tenantId") String tenantId);

  List<WorkerGroupStatusCountView> workerGroupStatusCounts(@Param("tenantId") String tenantId);

  List<ActivePartitionView> activePartitionsByWorker(@Param("tenantId") String tenantId);

  List<SeverityCountView> alertSeverityCounts(@Param("tenantId") String tenantId);

  List<ExecutionProgressView> executionProgress(
      @Param("tenantId") String tenantId,
      @Param("jobCode") String jobCode,
      @Param("bizDate") String bizDate);

  // ── 日趋势 ──────────────────────────────────────────────────────────────────

  List<DayStatusCountView> jobDailyTrend(
      @Param("tenantId") String tenantId, @Param("days") int days);

  List<DayCountView> triggerDailyTrend(@Param("tenantId") String tenantId, @Param("days") int days);

  List<DaySeverityCountView> alertDailyTrend(
      @Param("tenantId") String tenantId, @Param("days") int days);

  List<SlaDayView> slaDailyTrend(@Param("tenantId") String tenantId, @Param("days") int days);

  // ── 配置依赖反查 ────────────────────────────────────────────────────────────

  List<ConfigDependentView> jobsByQueueCode(
      @Param("tenantId") String tenantId, @Param("configCode") String configCode);

  List<ConfigDependentView> jobsByCalendarCode(
      @Param("tenantId") String tenantId, @Param("configCode") String configCode);

  List<ConfigDependentView> jobsByWindowCode(
      @Param("tenantId") String tenantId, @Param("configCode") String configCode);

  List<ConfigDependentView> jobsByWorkerGroup(
      @Param("tenantId") String tenantId, @Param("configCode") String configCode);

  // ── 计数 ────────────────────────────────────────────────────────────────────

  Long countJobDefinitions(@Param("tenantId") String tenantId);

  Long countRecentJobInstances(@Param("tenantId") String tenantId, @Param("days") int days);

  Long countWorkflowDefinitions(@Param("tenantId") String tenantId);

  Long countRecentFiles(@Param("tenantId") String tenantId, @Param("days") int days);

  Long countFileChannels(@Param("tenantId") String tenantId);

  Long countFileTemplates(@Param("tenantId") String tenantId);

  // ── SLA ────────────────────────────────────────────────────────────────────

  SlaStatsView slaStats(@Param("tenantId") String tenantId, @Param("days") int days);

  List<SlaJobReportView> slaJobReport(@Param("tenantId") String tenantId, @Param("days") int days);
}
