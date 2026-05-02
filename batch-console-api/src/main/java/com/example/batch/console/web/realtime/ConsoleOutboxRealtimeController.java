package com.example.batch.console.web.realtime;

import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeEventHub;
import com.example.batch.console.support.auth.ConsoleTenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Outbox 相关实时订阅入口。
 *
 * <p>包含重试视图和投递视图，两条流都直接消费统一实时事件总线。
 */
@RestController
@Validated
@RequestMapping("/api/console/stream")
@PreAuthorize(
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleOutboxRealtimeController {

  private final ConsoleRealtimeEventHub realtimeEventHub;
  private final ConsoleTenantGuard tenantGuard;

  @GetMapping(path = "/outbox-retries/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter outboxRetries(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "eventType", required = false) String eventType,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "heartbeatMillis", required = false) Long heartbeatMillis) {
    return realtimeEventHub.subscribe(
        tenantGuard.resolveTenant(tenantId), "outbox-retries", eventType, cursor, heartbeatMillis);
  }

  @GetMapping(path = "/outbox-deliveries/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter outboxDeliveries(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "eventType", required = false) String eventType,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "heartbeatMillis", required = false) Long heartbeatMillis) {
    return realtimeEventHub.subscribe(
        tenantGuard.resolveTenant(tenantId),
        "outbox-deliveries",
        eventType,
        cursor,
        heartbeatMillis);
  }
}
