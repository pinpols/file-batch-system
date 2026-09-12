package io.github.pinpols.batch.console.domain.observability.web;

import io.github.pinpols.batch.common.dto.CommonResponse;
import io.github.pinpols.batch.common.enums.ResultCode;
import io.github.pinpols.batch.common.exception.BizException;
import io.github.pinpols.batch.common.logging.BatchMdc;
import io.github.pinpols.batch.common.logging.StructuredLogField;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.domain.observability.web.request.FrontendTelemetryRequest;
import io.github.pinpols.batch.console.domain.observability.web.request.FrontendTelemetryRequest.Event;
import io.github.pinpols.batch.console.service.ConsoleResponseFactory;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 前端遥测收集：接收前端埋点，通过 slf4j + MDC 输出结构化日志。 */
@RestController
@Validated
@RequestMapping("/api/console/telemetry")
@RequiredArgsConstructor
@Slf4j
public class ConsoleTelemetryController {

  private static final int MAX_PROPS_BYTES_PER_EVENT = 8 * 1024;
  private static final int MAX_PROPS_KEYS_PER_EVENT = 50;
  private static final int MAX_PROPS_DEPTH = 5;
  private static final int MAX_PROPS_LIST_ITEMS = 100;

  private final ConsoleResponseFactory responseFactory;

  @PostMapping("/events")
  public CommonResponse<Void> receiveEvents(@RequestBody @Valid FrontendTelemetryRequest request) {
    validateProps(request);
    BatchMdc.put(StructuredLogField.FRONTEND_APP, request.app());
    if (request.userId() != null) {
      BatchMdc.put(StructuredLogField.FRONTEND_USER_ID, request.userId());
    }
    try {
      for (Event event : request.events()) {
        BatchMdc.put(StructuredLogField.FRONTEND_EVENT_TYPE, event.type());
        BatchMdc.put(StructuredLogField.FRONTEND_PAGE, event.page());
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
          BatchMdc.removeAll(
              StructuredLogField.FRONTEND_EVENT_TYPE, StructuredLogField.FRONTEND_PAGE);
        }
      }
    } finally {
      BatchMdc.removeAll(StructuredLogField.FRONTEND_APP, StructuredLogField.FRONTEND_USER_ID);
    }
    return responseFactory.success(null);
  }

  private void validateProps(FrontendTelemetryRequest request) {
    for (Event event : request.events()) {
      Map<String, Object> props = event.props();
      if (props == null || props.isEmpty()) {
        continue;
      }
      if (props.size() > MAX_PROPS_KEYS_PER_EVENT) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.telemetry.props_too_large");
      }
      if (depth(props, 1) > MAX_PROPS_DEPTH) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.telemetry.props_too_deep");
      }
      int bytes = JsonUtils.toJson(props).getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_PROPS_BYTES_PER_EVENT) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.telemetry.props_too_large");
      }
    }
  }

  private int depth(Object value, int currentDepth) {
    if (value instanceof Map<?, ?> map) {
      int max = currentDepth;
      for (Object child : map.values()) {
        max = Math.max(max, depth(child, currentDepth + 1));
      }
      return max;
    }
    if (value instanceof List<?> list) {
      if (list.size() > MAX_PROPS_LIST_ITEMS) {
        throw BizException.of(ResultCode.INVALID_ARGUMENT, "error.telemetry.props_too_large");
      }
      int max = currentDepth;
      for (Object child : list) {
        max = Math.max(max, depth(child, currentDepth + 1));
      }
      return max;
    }
    return currentDepth;
  }
}
