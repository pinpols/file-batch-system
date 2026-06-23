package io.github.pinpols.batch.console.domain.file.web.realtime;

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
 * Pipeline 进度 dirty 事件订阅入口。
 *
 * <p>SSE 只提示前端刷新 {@code /queries/pipeline-progress} 快照，不直接推 rowsProcessed 明细。
 */
@RestController
@Validated
@RequestMapping("/api/console/stream")
@PreAuthorize(
    "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_TENANT_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsolePipelineProgressRealtimeController {

  private final ConsoleRealtimeEventHub realtimeEventHub;
  private final ConsoleTenantGuard tenantGuard;

  @GetMapping(path = "/pipeline-progress/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter pipelineProgress(
      @RequestParam("tenantId") String tenantId,
      @RequestParam(value = "eventType", required = false) String eventType,
      @RequestParam(value = "cursor", required = false) String cursor,
      @RequestParam(value = "heartbeatMillis", required = false) Long heartbeatMillis) {
    return realtimeEventHub.subscribe(
        tenantGuard.resolveTenant(tenantId),
        "pipeline-progress",
        eventType,
        cursor,
        heartbeatMillis);
  }
}
