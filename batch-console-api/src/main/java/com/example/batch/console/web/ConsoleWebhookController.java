package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.domain.entity.WebhookDeliveryLogEntity;
import com.example.batch.console.domain.entity.WebhookSubscriptionEntity;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.service.ConsoleWebhookService;
import com.example.batch.console.service.ConsoleWebhookService.CreateSubscriptionCommand;
import com.example.batch.console.service.ConsoleWebhookService.UpdateSubscriptionCommand;
import com.example.batch.console.support.ConsoleRequestMetadataResolver;
import com.example.batch.console.support.Idempotent;
import com.example.batch.console.web.request.ops.CreateWebhookRequest;
import com.example.batch.console.web.request.ops.UpdateWebhookRequest;
import jakarta.validation.Valid;
import java.util.List;
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

@RestController
@Validated
@RequestMapping("/api/console/webhooks")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
@Idempotent
public class ConsoleWebhookController {

  private final ConsoleWebhookService webhookService;
  private final ConsoleResponseFactory responseFactory;
  private final ConsoleRequestMetadataResolver requestMetadataResolver;

  @GetMapping
  public CommonResponse<List<WebhookSubscriptionEntity>> list(
      @RequestParam("tenantId") String tenantId) {
    return responseFactory.success(webhookService.listSubscriptions(tenantId));
  }

  @GetMapping("/delivery-logs")
  public CommonResponse<List<WebhookDeliveryLogEntity>> deliveryLogs(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "subscriptionId", required = false) Long subscriptionId,
      @RequestParam(value = "limit", defaultValue = "20") int limit) {
    return responseFactory.success(
        webhookService.deliveryLogs(tenantId, subscriptionId, Math.min(Math.max(limit, 1), 200)));
  }

  @GetMapping("/{id}")
  public CommonResponse<WebhookSubscriptionEntity> detail(
      @RequestParam("tenantId") String tenantId, @PathVariable Long id) {
    return responseFactory.success(webhookService.getSubscription(tenantId, id));
  }

  @PostMapping
  public CommonResponse<WebhookSubscriptionEntity> create(
      @RequestParam("tenantId") String tenantId, @Valid @RequestBody CreateWebhookRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    CreateSubscriptionCommand createCommand =
        CreateSubscriptionCommand.builder()
            .tenantId(tenantId)
            .name(request.name())
            .callbackUrl(request.callbackUrl())
            .eventTypes(String.join(",", request.eventTypes()))
            .secret(request.secret())
            .enabled(request.enabled() == null || request.enabled())
            .operator(operator)
            .build();
    return responseFactory.success(webhookService.createSubscription(createCommand));
  }

  @PutMapping("/{id}")
  public CommonResponse<WebhookSubscriptionEntity> update(
      @RequestParam("tenantId") String tenantId,
      @PathVariable Long id,
      @Valid @RequestBody UpdateWebhookRequest request) {
    String operator = requestMetadataResolver.current().operatorId();
    UpdateSubscriptionCommand updateCommand =
        UpdateSubscriptionCommand.builder()
            .tenantId(tenantId)
            .id(id)
            .callbackUrl(request.callbackUrl())
            .eventTypes(String.join(",", request.eventTypes()))
            .secret(request.secret())
            .enabled(request.enabled() == null || request.enabled())
            .operator(operator)
            .build();
    return responseFactory.success(webhookService.updateSubscription(updateCommand));
  }

  @DeleteMapping("/{id}")
  public CommonResponse<Void> delete(
      @RequestParam("tenantId") String tenantId, @PathVariable Long id) {
    webhookService.deleteSubscription(tenantId, id);
    return responseFactory.success(null);
  }
}
