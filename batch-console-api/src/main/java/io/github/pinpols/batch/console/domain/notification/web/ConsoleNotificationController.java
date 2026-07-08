package io.github.pinpols.batch.console.domain.notification.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.console.domain.notification.application.ConsoleNotificationApplicationService;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpdateRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.NotificationChannelUpsertRequest;
import io.github.pinpols.batch.console.domain.notification.web.request.SubscriptionRuleUpsertRequest;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import io.github.pinpols.batch.console.support.web.Idempotent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 通知订阅管理 REST：通知渠道（EMAIL/钉钉/企微/Webhook）+ 订阅规则 + 投递日志。 */
@RestController
@Validated
@RequestMapping("/api/console/notifications")
@RequiredArgsConstructor
@Idempotent
public class ConsoleNotificationController {

  private final ConsoleNotificationApplicationService service;
  private final ConsoleResponseFactory responseFactory;

  @GetMapping("/channels")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<List<Map<String, Object>>> listChannels(
      @RequestParam @NotBlank String tenantId) {
    return responseFactory.success(service.listChannels(tenantId));
  }

  @GetMapping("/channels/{channelCode}")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<Map<String, Object>> getChannel(
      @RequestParam @NotBlank String tenantId, @PathVariable String channelCode) {
    return responseFactory.success(service.getChannel(tenantId, channelCode));
  }

  @PostMapping("/channels")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Void> createChannel(
      @RequestParam @NotBlank String tenantId,
      @Valid @RequestBody NotificationChannelUpsertRequest request) {
    service.createChannel(tenantId, request);
    return responseFactory.success(null);
  }

  @PutMapping("/channels/{channelCode}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Void> updateChannel(
      @RequestParam @NotBlank String tenantId,
      @PathVariable String channelCode,
      @Valid @RequestBody NotificationChannelUpdateRequest request) {
    service.updateChannel(tenantId, channelCode, request);
    return responseFactory.success(null);
  }

  @DeleteMapping("/channels/{channelCode}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Void> deleteChannel(
      @RequestParam @NotBlank String tenantId, @PathVariable String channelCode) {
    service.deleteChannel(tenantId, channelCode);
    return responseFactory.success(null);
  }

  @PostMapping("/channels/{channelCode}/test")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Map<String, Object>> testChannel(
      @RequestParam @NotBlank String tenantId, @PathVariable String channelCode) {
    return responseFactory.success(service.testChannel(tenantId, channelCode));
  }

  @GetMapping("/rules")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<List<Map<String, Object>>> listRules(
      @RequestParam @NotBlank String tenantId) {
    return responseFactory.success(service.listRules(tenantId));
  }

  @GetMapping("/rules/{ruleId}")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<Map<String, Object>> getRule(
      @RequestParam @NotBlank String tenantId, @PathVariable Long ruleId) {
    return responseFactory.success(service.getRule(tenantId, ruleId));
  }

  @PostMapping("/rules")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Void> createRule(
      @RequestParam @NotBlank String tenantId,
      @Valid @RequestBody SubscriptionRuleUpsertRequest request) {
    service.createRule(tenantId, request);
    return responseFactory.success(null);
  }

  @PutMapping("/rules/{ruleId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Void> updateRule(
      @RequestParam @NotBlank String tenantId,
      @PathVariable Long ruleId,
      @Valid @RequestBody SubscriptionRuleUpsertRequest request) {
    service.updateRule(tenantId, ruleId, request);
    return responseFactory.success(null);
  }

  @DeleteMapping("/rules/{ruleId}")
  @PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_TENANT_ADMIN')")
  public CommonResponse<Void> deleteRule(
      @RequestParam @NotBlank String tenantId, @PathVariable Long ruleId) {
    service.deleteRule(tenantId, ruleId);
    return responseFactory.success(null);
  }

  @GetMapping("/delivery-logs")
  @PreAuthorize(
      "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN'," + " 'ROLE_TENANT_USER')")
  public CommonResponse<List<Map<String, Object>>> deliveryLogs(
      @RequestParam @NotBlank String tenantId,
      @RequestParam(defaultValue = "100") @Positive @Max(500) int limit) {
    return responseFactory.success(service.deliveryLogs(tenantId, limit));
  }
}
