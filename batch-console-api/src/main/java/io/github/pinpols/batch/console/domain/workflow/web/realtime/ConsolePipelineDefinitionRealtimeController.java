package io.github.pinpols.batch.console.domain.workflow.web.realtime;

import io.github.pinpols.batch.console.domain.observability.realtime.ConsoleRealtimeEventHub;
import io.github.pinpols.batch.console.domain.rbac.support.ConsoleTenantGuard;
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
 * 流水线定义的实时订阅入口。
 *
 * <p>保存、启停、更新后由服务层发布事件，这里只负责订阅。
 */
@RestController
@Validated
@RequestMapping("/api/console/pipeline-definitions")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@RequiredArgsConstructor
public class ConsolePipelineDefinitionRealtimeController {

  private final ConsoleRealtimeEventHub realtimeEventHub;
  private final ConsoleTenantGuard tenantGuard;

  @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "eventType", required = false) String eventType,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "heartbeatMillis", required = false) Long heartbeatMillis) {
    return realtimeEventHub.subscribe(
        tenantGuard.resolveTenant(tenantId),
        "pipeline-definitions",
        eventType,
        cursor,
        heartbeatMillis);
  }
}
