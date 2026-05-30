package com.example.batch.console.domain.job.web.realtime;

import com.example.batch.console.domain.observability.realtime.ConsoleRealtimeEventHub;
import com.example.batch.console.domain.rbac.support.ConsoleTenantGuard;
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
 * 作业实例的实时订阅入口。
 *
 * <p>该控制器只负责把 SSE 连接挂到实时事件总线上，不承载业务写逻辑。
 */
@RestController
@Validated
@RequestMapping("/api/console/stream")
@PreAuthorize(
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleJobInstanceRealtimeController {

  private final ConsoleRealtimeEventHub realtimeEventHub;
  private final ConsoleTenantGuard tenantGuard;

  @GetMapping(path = "/job-instances/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter jobInstances(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "eventType", required = false) String eventType,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "heartbeatMillis", required = false) Long heartbeatMillis) {
    return realtimeEventHub.subscribe(
        tenantGuard.resolveTenant(tenantId), "job-instances", eventType, cursor, heartbeatMillis);
  }
}
