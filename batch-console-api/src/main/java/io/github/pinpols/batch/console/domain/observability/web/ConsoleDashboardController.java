package io.github.pinpols.batch.console.domain.observability.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleAlertTrendResponse;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleExecutionProgressResponse;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleJobStatsResponse;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleSlaComplianceResponse;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleSlaReportResponse;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleTenantUsageResponse;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleTriggerStatsResponse;
import io.github.pinpols.batch.console.domain.observability.application.contract.response.ConsoleWorkerLoadResponse;
import io.github.pinpols.batch.console.domain.observability.service.ConsoleDashboardQueryService;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.AlertTrendView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.ExecutionProgressView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.JobStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaComplianceView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.SlaReportView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TenantUsageView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.TriggerStatsView;
import io.github.pinpols.batch.console.domain.observability.view.dashboard.WorkerLoadView;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleSecurityExpressions;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/console/dashboard")
@PreAuthorize(ConsoleSecurityExpressions.ANY_CONSOLE_ROLE)
@RequiredArgsConstructor
public class ConsoleDashboardController {

  private final ConsoleDashboardQueryService queryService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/job-stats")
  public CommonResponse<ConsoleJobStatsResponse> jobStats(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    JobStatsView stats = queryService.jobStats(tenantId, days);
    ConsoleJobStatsResponse response = ConsoleJobStatsResponse.from(stats);
    return responseFactory.success(response);
  }

  @GetMapping("/trigger-stats")
  public CommonResponse<ConsoleTriggerStatsResponse> triggerStats(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    TriggerStatsView stats = queryService.triggerStats(tenantId, days);
    ConsoleTriggerStatsResponse response = ConsoleTriggerStatsResponse.from(stats);
    return responseFactory.success(response);
  }

  @GetMapping("/worker-load")
  public CommonResponse<ConsoleWorkerLoadResponse> workerLoad(
      @RequestParam("tenantId") String tenantId) {
    WorkerLoadView workerLoad = queryService.workerLoad(tenantId);
    ConsoleWorkerLoadResponse response = ConsoleWorkerLoadResponse.from(workerLoad);
    return responseFactory.success(response);
  }

  @GetMapping("/alert-trend")
  public CommonResponse<ConsoleAlertTrendResponse> alertTrend(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    AlertTrendView alertTrend = queryService.alertTrend(tenantId, days);
    ConsoleAlertTrendResponse response = ConsoleAlertTrendResponse.from(alertTrend);
    return responseFactory.success(response);
  }

  @GetMapping("/sla-compliance")
  public CommonResponse<ConsoleSlaComplianceResponse> slaCompliance(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    SlaComplianceView compliance = queryService.slaCompliance(tenantId, days);
    ConsoleSlaComplianceResponse response = ConsoleSlaComplianceResponse.from(compliance);
    return responseFactory.success(response);
  }

  /** SLA 报表：按 job 维度统计成功率、SLA 达标率、平均/最大耗时。 */
  @GetMapping("/sla-report")
  public CommonResponse<ConsoleSlaReportResponse> slaReport(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    SlaReportView report = queryService.slaReport(tenantId, days);
    ConsoleSlaReportResponse response = ConsoleSlaReportResponse.from(report);
    return responseFactory.success(response);
  }

  /** 执行进度查询（轻量）：按 jobCode + bizDate 返回实例进度。面向业务方。 */
  @GetMapping("/execution-progress")
  public CommonResponse<List<ConsoleExecutionProgressResponse>> executionProgress(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("jobCode") String jobCode,
      @RequestParam("bizDate") String bizDate) {
    List<ExecutionProgressView> progress = queryService.executionProgress(tenantId, jobCode, bizDate);
    List<ConsoleExecutionProgressResponse> responses =
        progress.stream().map(ConsoleExecutionProgressResponse::from).toList();
    return responseFactory.success(responses);
  }

  /** 租户用量统计：配置数量 + 近期实例/文件处理量。 */
  @GetMapping("/tenant-usage")
  public CommonResponse<ConsoleTenantUsageResponse> tenantUsage(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "30") int days) {
    TenantUsageView usage = queryService.tenantUsage(tenantId, days);
    ConsoleTenantUsageResponse response = ConsoleTenantUsageResponse.from(usage);
    return responseFactory.success(response);
  }
}
