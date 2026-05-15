package com.example.batch.console.web;

import com.example.batch.console.application.report.ConsoleReportExcelApplicationService;
import com.example.batch.console.web.query.AuditLogQueryRequest;
import com.example.batch.console.web.query.ConfigChangeLogQueryRequest;
import com.example.batch.console.web.query.ConfigReleaseQueryRequest;
import com.example.batch.console.web.query.OutboxDeliveryLogQueryRequest;
import com.example.batch.console.web.query.OutboxRetryLogQueryRequest;
import com.example.batch.console.web.query.SecretVersionQueryRequest;
import com.example.batch.console.web.query.WorkerRegistryQueryRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 控制台报表 Excel 导出 REST：配置、审计、调度快照、Worker、Outbox 等。 */
@RestController
@Validated
@RequestMapping("/api/console/reports/excel")
@PreAuthorize(
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleReportExcelController {

  private final ConsoleReportExcelApplicationService applicationService;

  /** 导出配置发布单 Excel。 */
  @GetMapping("/config-releases")
  public ResponseEntity<StreamingResponseBody> configReleases(
      @ModelAttribute ConfigReleaseQueryRequest request) {
    return applicationService.exportConfigReleases(request);
  }

  /** 导出密钥版本 Excel。 */
  @GetMapping("/secrets")
  public ResponseEntity<StreamingResponseBody> secrets(
      @ModelAttribute SecretVersionQueryRequest request) {
    return applicationService.exportSecretVersions(request);
  }

  /** 导出配置变更日志 Excel。 */
  @GetMapping("/change-logs")
  public ResponseEntity<StreamingResponseBody> configChangeLogs(
      @ModelAttribute ConfigChangeLogQueryRequest request) {
    return applicationService.exportConfigChangeLogs(request);
  }

  /** 导出审计日志 Excel。 */
  @GetMapping("/audits")
  public ResponseEntity<StreamingResponseBody> audits(
      @ModelAttribute AuditLogQueryRequest request) {
    return applicationService.exportAuditLogs(request);
  }

  /** 导出调度快照 Excel。 */
  @GetMapping("/scheduler-snapshot")
  public ResponseEntity<StreamingResponseBody> schedulerSnapshot(
      @RequestParam("tenantId") String tenantId) {
    return applicationService.exportSchedulerSnapshot(tenantId);
  }

  /** 导出调度快照历史 Excel。 */
  @GetMapping("/scheduler-history")
  public ResponseEntity<StreamingResponseBody> schedulerHistory(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    return applicationService.exportSchedulerSnapshotHistory(tenantId, limit);
  }

  /** 导出 Worker 列表 Excel。 */
  @GetMapping("/workers")
  public ResponseEntity<StreamingResponseBody> workers(
      @ModelAttribute WorkerRegistryQueryRequest request) {
    return applicationService.exportWorkers(request);
  }

  /** 导出 Outbox 重试日志 Excel。 */
  @GetMapping("/outbox-retries")
  public ResponseEntity<StreamingResponseBody> outboxRetries(
      @ModelAttribute OutboxRetryLogQueryRequest request) {
    return applicationService.exportOutboxRetries(request);
  }

  /** 导出 Outbox 投递日志 Excel。 */
  @GetMapping("/outbox-deliveries")
  public ResponseEntity<StreamingResponseBody> outboxDeliveries(
      @ModelAttribute OutboxDeliveryLogQueryRequest request) {
    return applicationService.exportOutboxDeliveries(request);
  }
}
