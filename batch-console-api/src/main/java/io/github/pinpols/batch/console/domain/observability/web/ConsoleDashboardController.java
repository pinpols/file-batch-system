package io.github.pinpols.batch.console.domain.observability.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.observability.service.ConsoleDashboardQueryService;
import io.github.pinpols.batch.console.domain.observability.web.response.ConsoleAlertTrendResponse;
import io.github.pinpols.batch.console.domain.observability.web.response.ConsoleExecutionProgressResponse;
import io.github.pinpols.batch.console.domain.observability.web.response.ConsoleJobStatsResponse;
import io.github.pinpols.batch.console.domain.observability.web.response.ConsoleSlaComplianceResponse;
import io.github.pinpols.batch.console.domain.observability.web.response.ConsoleSlaReportResponse;
import io.github.pinpols.batch.console.domain.observability.web.response.ConsoleTenantUsageResponse;
import io.github.pinpols.batch.console.domain.observability.web.response.ConsoleTriggerStatsResponse;
import io.github.pinpols.batch.console.domain.observability.web.response.ConsoleWorkerLoadResponse;
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
@PreAuthorize(
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleDashboardController {

  private final ConsoleDashboardQueryService queryService;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/job-stats")
  public CommonResponse<ConsoleJobStatsResponse> jobStats(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    return responseFactory.success(
        ConsoleJobStatsResponse.from(queryService.jobStats(tenantId, days)));
  }

  @GetMapping("/trigger-stats")
  public CommonResponse<ConsoleTriggerStatsResponse> triggerStats(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    return responseFactory.success(
        ConsoleTriggerStatsResponse.from(queryService.triggerStats(tenantId, days)));
  }

  @GetMapping("/worker-load")
  public CommonResponse<ConsoleWorkerLoadResponse> workerLoad(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(
        ConsoleWorkerLoadResponse.from(queryService.workerLoad(tenantId)));
  }

  @GetMapping("/alert-trend")
  public CommonResponse<ConsoleAlertTrendResponse> alertTrend(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    return responseFactory.success(
        ConsoleAlertTrendResponse.from(queryService.alertTrend(tenantId, days)));
  }

  @GetMapping("/sla-compliance")
  public CommonResponse<ConsoleSlaComplianceResponse> slaCompliance(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    return responseFactory.success(
        ConsoleSlaComplianceResponse.from(queryService.slaCompliance(tenantId, days)));
  }

  /** SLA 报表：按 job 维度统计成功率、SLA 达标率、平均/最大耗时。 */
  @GetMapping("/sla-report")
  public CommonResponse<ConsoleSlaReportResponse> slaReport(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "7") int days) {
    return responseFactory.success(
        ConsoleSlaReportResponse.from(queryService.slaReport(tenantId, days)));
  }

  /** 执行进度查询（轻量）：按 jobCode + bizDate 返回实例进度。面向业务方。 */
  @GetMapping("/execution-progress")
  public CommonResponse<List<ConsoleExecutionProgressResponse>> executionProgress(
      @RequestParam("tenantId") String tenantId,
      @RequestParam("jobCode") String jobCode,
      @RequestParam("bizDate") String bizDate) {
    return responseFactory.success(
        queryService.executionProgress(tenantId, jobCode, bizDate).stream()
            .map(ConsoleExecutionProgressResponse::from)
            .toList());
  }

  /** 租户用量统计：配置数量 + 近期实例/文件处理量。 */
  @GetMapping("/tenant-usage")
  public CommonResponse<ConsoleTenantUsageResponse> tenantUsage(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "days", defaultValue = "30") int days) {
    return responseFactory.success(
        ConsoleTenantUsageResponse.from(queryService.tenantUsage(tenantId, days)));
  }
}
