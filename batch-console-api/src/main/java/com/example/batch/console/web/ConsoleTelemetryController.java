package com.example.batch.console.web;

import com.example.batch.common.dto.CommonResponse;
import com.example.batch.common.utils.JsonUtils;
import com.example.batch.console.service.ConsoleResponseFactory;
import com.example.batch.console.web.request.FrontendTelemetryRequest.Event;
import com.example.batch.console.web.request.auth.FrontendTelemetryRequest;
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
          String propsStr = event.props() != null ? JsonUtils.toJson(event.props()) : "";
          if ("error".equals(event.type())) {
            log.error(
                "[frontend:error] {} | page={} props={}", event.name(), event.page(), propsStr);
          } else {
            log.info(
                "[frontend:{}] {} | page={} props={}",
                event.type(),
                event.name(),
                event.page(),
                propsStr);
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
