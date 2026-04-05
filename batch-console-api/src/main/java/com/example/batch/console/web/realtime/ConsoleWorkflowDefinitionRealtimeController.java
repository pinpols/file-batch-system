package com.example.batch.console.web.realtime;

import com.example.batch.console.infrastructure.realtime.ConsoleRealtimeEventHub;
import com.example.batch.console.support.ConsoleTenantGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Validated
@RequestMapping("/api/console/workflow-definitions")
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN')")
@RequiredArgsConstructor
public class ConsoleWorkflowDefinitionRealtimeController {

    private final ConsoleRealtimeEventHub realtimeEventHub;
    private final ConsoleTenantGuard tenantGuard;

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestParam("tenantId") String tenantId,
                             @RequestParam(value = "eventType", required = false) String eventType,
                             @RequestParam(value = "cursor", required = false) String cursor,
                             @RequestParam(value = "heartbeatMillis", required = false) Long heartbeatMillis) {
        return realtimeEventHub.subscribe(tenantGuard.resolveTenant(tenantId), "workflow-definitions", eventType, cursor, heartbeatMillis);
    }
}
