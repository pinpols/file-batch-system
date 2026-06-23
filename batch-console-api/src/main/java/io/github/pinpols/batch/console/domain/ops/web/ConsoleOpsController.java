package io.github.pinpols.batch.console.domain.ops.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.audit.support.AuditAction;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOpsApplicationService;
import io.github.pinpols.batch.console.domain.ops.application.ConsoleOutboxOpsApplicationService;
import io.github.pinpols.batch.console.domain.ops.service.ConsoleKafkaLagQueryService;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOpsSummaryResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxCleanupResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxRepublishResponse;
import io.github.pinpols.batch.console.domain.ops.web.response.ConsoleOutboxStatsResponse;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 控制台运维总览 REST：租户维度运行摘要。 */
@RestController
@Validated
@RequestMapping("/api/console/ops")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleOpsController {

  private final ConsoleOpsApplicationService opsApplicationService;
  private final ConsoleOutboxOpsApplicationService outboxOpsService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleKafkaLagQueryService kafkaLagQueryService;

  /** 租户运维摘要（Redis 缓存 10s，避免多用户同时刷导致 DB 重复聚合）。 */
  @GetMapping("/summary")
  public CommonResponse<ConsoleOpsSummaryResponse> summary(
      @RequestParam @NotBlank String tenantId) {
    return responseFactory.success(opsApplicationService.summary(tenantId));
  }

  /** Kafka consumer group 积压查询。 */
  @GetMapping("/kafka-lag")
  public CommonResponse<List<Map<String, Object>>> kafkaConsumerLag(
      @RequestParam(value = "groupId", required = false) String groupId) {
    return responseFactory.success(kafkaLagQueryService.consumerGroupLags(groupId));
  }

  /** Outbox 积压统计（按 publish_status 分组）。 */
  @GetMapping("/outbox/stats")
  public CommonResponse<ConsoleOutboxStatsResponse> outboxStats(
      @RequestParam @NotBlank String tenantId) {
    return responseFactory.success(outboxOpsService.stats(tenantId));
  }

  /** 清理已发布 / 已放弃的 outbox 事件（保留最近 retainDays 天）。 */
  @PostMapping("/outbox/cleanup")
  @AuditAction(action = "outbox.cleanup", aggregateType = "outbox", targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleOutboxCleanupResponse> outboxCleanup(
      @RequestParam @NotBlank String tenantId,
      @RequestParam(defaultValue = "7") @Positive int retainDays) {
    return responseFactory.success(outboxOpsService.cleanup(tenantId, retainDays));
  }

  /** 手动重投指定 outbox 事件（仅 FAILED / GIVE_UP 状态可重投）。 */
  @PostMapping("/outbox/republish")
  @AuditAction(
      action = "outbox.republish",
      aggregateType = "outbox",
      targetTenantParam = "#tenantId")
  public CommonResponse<ConsoleOutboxRepublishResponse> outboxRepublish(
      @RequestParam @NotBlank String tenantId, @RequestBody @NotEmpty List<Long> ids) {
    return responseFactory.success(outboxOpsService.republish(tenantId, ids));
  }
}
