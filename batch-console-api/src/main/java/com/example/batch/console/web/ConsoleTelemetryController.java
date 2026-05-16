package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.auth.FrontendTelemetryRequest;
import com.example.batch.console.web.request.auth.FrontendTelemetryRequest.Event;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 前端遥测日志收集：接收前端埋点，通过 slf4j + MDC 输出结构化日志，由 Promtail 采集进 Loki。 */
@RestController
@Validated
@RequestMapping("/api/console/telemetry")
@RequiredArgsConstructor
@Slf4j
public class ConsoleTelemetryController {

  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/events")
  public CommonResponse<Void> receiveEvents(@RequestBody @Valid FrontendTelemetryRequest request) {
    MDC.put("frontendApp", request.app());
    if (request.userId() != null) {
      MDC.put("frontendUserId", request.userId());
    }
    try {
      for (Event event : request.events()) {
        MDC.put("frontendEventType", event.type());
        MDC.put("frontendPage", event.page() != null ? event.page() : "");
        try {
          // P2-2(2026-05-16):不再把 props 整体序列化进结构化日志,只记 props key 数量。
          // 原写法 props={整个 JSON} 让登录用户任意撑大日志,且潜在把 token/密码等敏感字段
          // 顺手写进 Loki。结构化追踪如确需 props,可加专门的 telemetry table / OTLP 出口,
          // 不要再 piggyback 应用日志。
          int propsKeys = event.props() == null ? 0 : event.props().size();
          if ("error".equals(event.type())) {
            log.error(
                "[frontend:error] {} | page={} propsKeys={}",
                event.name(),
                event.page(),
                propsKeys);
          } else {
            log.info(
                "[frontend:{}] {} | page={} propsKeys={}",
                event.type(),
                event.name(),
                event.page(),
                propsKeys);
          }
        } finally {
          MDC.remove("frontendEventType");
          MDC.remove("frontendPage");
        }
      }
    } finally {
      MDC.remove("frontendApp");
      MDC.remove("frontendUserId");
    }
    return responseFactory.success(null);
  }
}
