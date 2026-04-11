package com.example.batch.console.web.realtime;

import com.example.batch.console.infrastructure.realtime.ConsoleOpsSummaryRealtimeStream;

import jakarta.validation.constraints.NotBlank;

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
 * 控制台运维摘要实时流。
 *
 * <p>该 SSE 入口从 Redis Streams 消费共享事件；首屏查询仍然可以走普通负载均衡。
 */
@RestController
@Validated
@RequestMapping("/api/console/ops")
@PreAuthorize(
        "hasAnyAuthority('ROLE_ADMIN', 'ROLE_AUDITOR', 'ROLE_CONFIG_ADMIN', 'ROLE_TENANT_USER')")
@RequiredArgsConstructor
public class ConsoleOpsRealtimeController {

    private final ConsoleOpsSummaryRealtimeStream summaryRealtimeStream;

    @GetMapping(value = "/summary/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter summaryEvents(
            @RequestParam @NotBlank String tenantId,
            @RequestParam(value = "heartbeatMillis", required = false) Long heartbeatMillis,
            @RequestParam(value = "initialSnapshot", defaultValue = "true")
                    boolean initialSnapshot) {
        return summaryRealtimeStream.subscribe(tenantId, heartbeatMillis, initialSnapshot);
    }
}
